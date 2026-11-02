package com.tvboard.ime.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.util.Locale

/**
 * 拼音词库。
 *
 * 数据来自 `scripts/build-dict.py` 产出的 `assets/dict.db`：
 * 6.3 万词条 / 3.3 MB，全部由 MIT 许可的数据源（pinyin-data、pypinyin、jieba）构建，
 * 不含 GPL 词库，可随闭源分发。
 *
 * 表结构：
 *   word(text TEXT, pinyin TEXT, abbr TEXT, freq INTEGER)
 *   idx_pinyin ON word(pinyin)   -- 全拼前缀检索
 *   idx_abbr   ON word(abbr) WHERE abbr <> ''  -- 简拼检索
 *
 * 用 SQLite 而非内存全量反序列化：3 MB 词库在 512MB 设备上全量载入会引发 GC 抖动，
 * 而 IME 弹出时主线程一抖，第一次按键就有明显延迟。
 */
class DictRepository(private val context: Context) {

    private var database: SQLiteDatabase? = null
    private var openAttempted = false

    /**
     * 前缀检索。
     *
     * 排序按「全拼完全相同 → 词频」：输入 dianshi 时「电视」必须排在「电视台」前面，
     * 只按词频排会把它压到「电视剧」之后。
     */
    fun candidates(query: String, limit: Int = 6): List<String> {
        if (query.isEmpty()) return emptyList()
        val lower = query.lowercase(Locale.ROOT)
        val result = LinkedHashSet<String>(limit * 2)

        val db = openDatabase()
        if (db != null) {
            try {
                // 全拼前缀。上界用 \uFFFF 而不是「末位字符 +1」：
                // 末位是 z 时加一会跳到 ASCII 小写区之后，区间直接失效。
                query(
                    db,
                    "SELECT text FROM word WHERE pinyin >= ? AND pinyin < ? " +
                        "ORDER BY (pinyin = ?) DESC, freq DESC LIMIT 24",
                    arrayOf(lower, lower + "\uFFFF", lower),
                    result, limit
                )
                // 简拼。仅当全拼没凑够候选时才查，省掉一次索引遍历
                if (result.size < limit) {
                    query(
                        db,
                        "SELECT text FROM word WHERE abbr = ? ORDER BY freq DESC LIMIT 8",
                        arrayOf(lower),
                        result, limit
                    )
                }
            } catch (ignored: Throwable) {
                // 词库被替换或损坏时走内置兜底
            }
        }

        if (result.isEmpty()) {
            for (entry in BUILTIN) {
                if (result.size >= limit) break
                if (entry.second.startsWith(lower)) result.add(entry.first)
            }
        }
        return result.take(limit)
    }

    private fun query(
        db: SQLiteDatabase,
        sql: String,
        args: Array<String>,
        out: MutableSet<String>,
        limit: Int
    ) {
        db.rawQuery(sql, args).use { cursor ->
            while (cursor.moveToNext() && out.size < limit) {
                val text = cursor.getString(0)
                if (!text.isNullOrEmpty()) out.add(text)
            }
        }
    }

    /**
     * 懒加载：首次查询才打开数据库，避免 IME 视图创建时占用主线程。
     *
     * dict.version 是构建脚本生成的 `sha1-字节数` 戳。APK 升级后词库可能变了，
     * 若只判断「文件存在」就会一直用旧词库，新词永远出不来。
     */
    private fun openDatabase(): SQLiteDatabase? {
        if (openAttempted) return database
        openAttempted = true
        try {
            val target = File(context.filesDir, "dict.db")
            val version = runCatching {
                context.assets.open("dict.version").bufferedReader().use { it.readLine() }
            }.getOrNull()
            val installed = version != null && context
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_VERSION, null) == version

            if (!installed || !target.exists() || target.length() == 0L) {
                context.assets.open("dict.db").use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                if (version != null) {
                    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .edit().putString(KEY_VERSION, version).apply()
                }
            }
            database = SQLiteDatabase.openDatabase(
                target.absolutePath, null, SQLiteDatabase.OPEN_READONLY
            )
        } catch (ignored: Throwable) {
            database = null
        }
        return database
    }

    private companion object {
        const val PREFS = "dict"
        const val KEY_VERSION = "version"

        /** 兜底词表：仅在 dict.db 缺失或损坏时使用，覆盖电视高频场景 */
        val BUILTIN: List<Pair<String, String>> = listOf(
            "你好" to "nihao",
            "中国" to "zhongguo",
            "我们" to "women",
            "电视" to "dianshi",
            "电影" to "dianying",
            "输入" to "shuru",
            "输入法" to "shurufa",
            "搜索" to "sousuo",
            "设置" to "shezhi",
            "网络" to "wangluo",
            "密码" to "mima",
            "登录" to "denglu",
            "账号" to "zhanghao",
            "视频" to "shipin",
            "播放" to "bofang",
            "下载" to "xiazai",
            "文件" to "wenjian",
            "手机" to "shouji",
            "连接" to "lianjie",
            "二维码" to "erweima",
            "应用" to "yingyong",
            "安装" to "anzhuang",
            "版本" to "banben",
            "更新" to "gengxin",
            "确定" to "queding",
            "取消" to "quxiao",
            "返回" to "fanhui",
            "打开" to "dakai",
            "关闭" to "guanbi",
            "时间" to "shijian",
            "日期" to "riqi",
            "天气" to "tianqi",
            "新闻" to "xinwen",
            "游戏" to "youxi",
            "客厅" to "keting",
            "房间" to "fangjian",
            "键盘" to "jianpan",
            "遥控器" to "yaokongqi",
            "声音" to "shengyin",
            "画面" to "huamian",
            "会员" to "huiyuan",
            "音乐" to "yinyue",
            "投屏" to "touping",
            "机顶盒" to "jidinghe",
            "我" to "wo",
            "你" to "ni",
            "的" to "de",
            "是" to "shi",
            "在" to "zai",
            "有" to "you",
            "和" to "he",
            "不" to "bu",
            "人" to "ren",
            "大" to "da",
            "中" to "zhong",
            "国" to "guo"
        )
    }
}
