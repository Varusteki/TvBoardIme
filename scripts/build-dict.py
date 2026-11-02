#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""构建 IME 拼音词库 assets/dict.db。

设计目标
--------
1. **许可证干净**：只使用 MIT 数据源，避免 GPL 词库（rime-ice）传染到分发包。
2. **多音字正确**：词组拼音优先取 pypinyin 的 phrases_dict（4.7 万条人工校正），
   只有缺失时才退化为逐字主读音拼接。
3. **体积可控**：按词频分长度配额截断，产出的 db 控制在 3MB 内，
   512MB 内存的老电视上不会因为词库过大而频繁 GC。

数据源（全部 MIT）
------------------
- pinyin-data   https://github.com/mozillazg/pinyin-data     字符多读音
- python-pinyin https://github.com/mozillazg/python-pinyin   词组拼音（打包在 wheel 内）
- jieba         https://github.com/fxsjy/jieba               词频 + 词表

用法
----
    python scripts/build-dict.py                 # 增量：命中缓存则不再下载
    python scripts/build-dict.py --refresh       # 强制重下数据源
    python scripts/build-dict.py --out /tmp/x.db # 指定输出

产物表结构（与 DictRepository 一致）
------------------------------------
    CREATE TABLE word(
        text   TEXT NOT NULL,   -- 词条
        pinyin TEXT NOT NULL,   -- 无声调全拼，ü 写作 v，音节间不加分隔
        abbr   TEXT NOT NULL,   -- 音节首字母，单词条为空串
        freq   INTEGER NOT NULL -- 词频，越大越靠前
    );
    CREATE INDEX idx_pinyin ON word(pinyin);
    CREATE INDEX idx_abbr   ON word(abbr);
"""

import argparse
import io
import json
import re
import shutil
import sqlite3
import subprocess
import sys
import unicodedata
import urllib.request
import zipfile
from collections import defaultdict
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
DEFAULT_OUT = ROOT / "app" / "src" / "main" / "assets" / "dict.db"
CACHE = HERE / ".cache" / "dict-src"

JIEBA_URL = "https://raw.githubusercontent.com/fxsjy/jieba/master/jieba/dict.txt"
PYPINYIN_PYPI = "https://pypi.org/pypi/pypinyin/json"

CJK = re.compile(r"^[\u4e00-\u9fff]+$")

# 每个长度的筛选规则：(词频下限, 最多收多少条)
# 依据 jieba 的实际词频分布设定：二字词中位数仅 8，三字词 3，
# 阈值压得太低会把大量生僻/噪声词塞进来，只增体积不增命中率。
RULES = {
    1: (5, 12000),
    2: (10, 30000),
    3: (10, 14000),
    4: (12, 9000),
    5: (40, 1600),
    6: (60, 600),
}

# 电视场景高频词，人工提权。电视机前的输入需求集中在搜索片名、
# 输入账号密码、连 Wi-Fi 这几类，通用词频表里它们排不进候选前几名。
TV_WORDS = {
    "电视": 300000, "电影": 300000, "电视剧": 260000, "投屏": 300000,
    "机顶盒": 260000, "遥控器": 260000, "二维码": 300000, "扫码": 280000,
    "投送": 240000, "文件": 260000, "视频": 300000, "播放": 280000,
    "搜索": 300000, "设置": 300000, "网络": 300000, "无线": 260000,
    "密码": 300000, "账号": 300000, "登录": 300000, "注册": 260000,
    "输入": 280000, "输入法": 280000, "键盘": 260000, "应用": 280000,
    "安装": 260000, "下载": 280000, "更新": 240000, "版本": 240000,
    "连接": 280000, "蓝牙": 240000, "会员": 240000, "音乐": 260000,
    "直播": 260000, "动漫": 220000, "综艺": 220000, "体育": 220000,
    "少儿": 200000, "收藏": 220000, "历史": 220000, "继续": 220000,
}

# 通用高频短语。jieba 词表是按新闻语料统计的，口语里的「这是/好的/是的」
# 这类跨词组合它基本不收，偏偏这些是用户最常打的东西，只能人工补。
# 值即最终词频：量级压在单字（几十万）之下、普通词（数千）之上。
CORE_PHRASES = {
    "这是": 420000, "那是": 380000, "好的": 460000, "是的": 430000,
    "不是": 480000, "没有": 480000, "可以": 460000, "知道": 420000,
    "什么": 500000, "怎么": 480000, "为什么": 440000, "怎么样": 400000,
    "谢谢": 440000, "再见": 380000, "你好": 420000, "对不起": 380000,
    "没关系": 360000, "不知道": 420000, "没办法": 340000, "请稍等": 340000,
    "稍等": 320000, "这个": 460000, "那个": 420000, "我们": 480000,
    "你们": 420000, "他们": 420000, "自己": 440000, "现在": 440000,
    "今天": 420000, "明天": 400000, "昨天": 380000, "时候": 400000,
    "多少": 400000, "哪里": 380000, "一个": 480000, "一下": 420000,
    "就是": 460000, "还是": 420000, "但是": 420000, "因为": 420000,
    "所以": 420000, "而且": 380000, "如果": 400000, "然后": 400000,
    "真的": 380000, "好像": 360000, "应该": 400000, "一定": 380000,
    "可能": 400000, "其实": 380000, "当然": 380000, "一起": 400000,
    "喜欢": 400000, "觉得": 400000, "希望": 400000, "需要": 400000,
    "开始": 400000, "结束": 340000, "确认": 340000, "返回": 340000,
    "取消": 340000, "打开": 360000, "关闭": 340000, "不要": 420000,
    "不用": 380000, "不会": 400000, "不能": 420000, "试试": 320000,
    "看看": 340000, "东西": 360000, "事情": 360000,
    "问题": 380000, "办法": 340000, "朋友": 400000, "孩子": 380000,
}

PRIORITY = {**CORE_PHRASES, **TV_WORDS}

# ü 在多数拼音输入法里用 v 输入，但用户也常直接敲 u。
# 对含 v 的词额外补一条 v→u 的替身记录，检索时两者都能命中。
UMLAUT_ALIAS = True


# ---------------------------------------------------------------- 数据源获取

def http_get(url: str, timeout: int = 120) -> bytes:
    """取二进制内容。

    先走 urllib；公司网络/沙箱里的 HTTP 代理只认 CONNECT 隧道时 urllib 会
    RemoteDisconnected，此时退回 curl（各平台自带，行为一致）。
    """
    req = urllib.request.Request(url, headers={"User-Agent": "tvboard-ime-dict/1.0"})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.read()
    except Exception as first:  # noqa: BLE001 - 具体异常类型各平台不一
        curl = shutil.which("curl")
        if not curl:
            raise first
        proc = subprocess.run(
            [curl, "-sSL", "--max-time", str(timeout), url],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        )
        if proc.returncode != 0 or not proc.stdout:
            raise RuntimeError(f"下载失败 {url}: {proc.stderr.decode('utf8', 'ignore')[:200]}")
        return proc.stdout


def fetch(url, dest: Path, refresh: bool) -> Path:
    if dest.exists() and not refresh and dest.stat().st_size > 0:
        print(f"  缓存命中 {dest.name} ({dest.stat().st_size} B)")
        return dest
    dest.parent.mkdir(parents=True, exist_ok=True)
    print(f"  下载 {url}")
    data = http_get(url)
    dest.write_bytes(data)
    print(f"  完成 {dest.name} ({len(data)} B)")
    return dest


def fetch_pypinyin(refresh: bool):
    """从 PyPI 取 pypinyin wheel，直接读出两份 JSON（免安装、免污染环境）。"""
    cached = CACHE / "phrases_dict.json"
    cached_char = CACHE / "pinyin_dict.json"
    if cached.exists() and cached_char.exists() and not refresh:
        print("  缓存命中 pypinyin 数据")
        return (json.loads(cached.read_text("utf8")),
                json.loads(cached_char.read_text("utf8")))

    print("  查询 PyPI pypinyin")
    meta = json.loads(http_get(PYPINYIN_PYPI, timeout=60))
    wheel = next((u for u in meta["urls"] if u["filename"].endswith(".whl")), None)
    if wheel is None:
        raise RuntimeError("PyPI 上没有找到 pypinyin 的 wheel")
    print(f"  下载 pypinyin {meta['info']['version']}")
    blob = http_get(wheel["url"])
    with zipfile.ZipFile(io.BytesIO(blob)) as z:
        phrases = json.loads(z.read("pypinyin/phrases_dict.json"))
        chars = json.loads(z.read("pypinyin/pinyin_dict.json"))
    cached.parent.mkdir(parents=True, exist_ok=True)
    cached.write_text(json.dumps(phrases, ensure_ascii=False), "utf8")
    cached_char.write_text(json.dumps(chars, ensure_ascii=False), "utf8")
    print(f"  完成 pypinyin：{len(phrases)} 词组 / {len(chars)} 字")
    return phrases, chars


# ---------------------------------------------------------------- 解析

def strip_tone(syllable: str) -> str:
    """把注音转成键盘可输入的字母：去声调、ü→v、只留 a-z。

    不能简单地「去掉所有组合符号」：ü 在 NFD 下是 u + U+0308，
    直接去符号会把「绿 lǜ」变成 lu，和「路 lù」撞车。
    """
    decomp = unicodedata.normalize("NFD", syllable)
    out = []
    i = 0
    while i < len(decomp):
        ch = decomp[i]
        if ch == "u" and i + 1 < len(decomp) and decomp[i + 1] == "\u0308":
            out.append("v")
            i += 2
            while i < len(decomp) and unicodedata.combining(decomp[i]):
                i += 1
            continue
        if unicodedata.combining(ch):
            i += 1
            continue
        out.append(ch.lower())
        i += 1
    return re.sub(r"[^a-z]", "", "".join(out))


def load_chars(pinyin_dict) -> dict:
    """codepoint → [无声调读音]，第一个为主读音。

    pinyin_dict.json 的值是逗号分隔的读音串（如 "zhǎng,cháng"），
    不是列表——按字符迭代会得到单个字母，词库会退化成全简拼。
    """
    out = {}
    for cp, readings in pinyin_dict.items():
        try:
            ch = chr(int(cp))
        except (TypeError, ValueError):
            continue
        if not CJK.match(ch):
            continue
        if isinstance(readings, str):
            readings = readings.split(",")
        seen, norm = set(), []
        for r in readings:
            s = strip_tone(str(r))
            if s and s not in seen:
                seen.add(s)
                norm.append(s)
        if norm:
            out[ch] = norm
    return out


def load_phrases(phrases_dict) -> dict:
    """词组 → (音节列表, 全拼, 首字母)。

    phrases_dict 的形状是 `[音节1候选, 音节2候选, ...]`，元素个数等于字数；
    每个音节可能是字符串，也可能是该音节多个读音组成的列表（取第一个）。
    曾按 `variants[0]` 取第一层，等于只取了一个音节，导致 99.99% 的词组被丢弃。
    """
    out = {}
    for word, items in phrases_dict.items():
        if not CJK.match(word) or not items:
            continue
        syllables = []
        for item in items:
            if isinstance(item, (list, tuple)):
                item = item[0] if item else ""
            s = strip_tone(str(item))
            if not s:
                syllables = []
                break
            syllables.append(s)
        if len(syllables) != len(word):
            continue
        out[word] = (syllables, "".join(syllables), "".join(s[0] for s in syllables))
    return out


def load_words(path: Path):
    """jieba dict.txt：`词 词频 词性`。只保留纯汉字、长度 1..6 的条目。"""
    words = {}
    with path.open(encoding="utf8", errors="ignore") as fp:
        for line in fp:
            parts = line.split()
            if len(parts) < 2:
                continue
            word = parts[0]
            if not CJK.match(word) or not 1 <= len(word) <= 6:
                continue
            try:
                freq = int(parts[1])
            except ValueError:
                continue
            if freq <= 0:
                continue
            if freq > words.get(word, 0):
                words[word] = freq
    return words


# ---------------------------------------------------------------- 组装

def build_rows(words, char_readings, phrases):
    """按长度配额挑词，生成待入库记录。"""
    # 提权表里的词可能压根不在 jieba 里（二维码、投屏、输入法都属于这种），
    # 光提权是提不出来的，得先补进来。
    for w in PRIORITY:
        if w not in words and CJK.match(w):
            words[w] = 1

    by_len = defaultdict(list)
    for word, freq in words.items():
        # 提权必须在阈值筛选之前生效，否则补进来的词会因 freq=1 被 min_freq 滤掉
        by_len[len(word)].append((max(freq, PRIORITY.get(word, 0)), word))

    picked, skipped, composed = [], 0, 0
    for length, (min_freq, cap) in RULES.items():
        bucket = [x for x in by_len.get(length, []) if x[0] >= min_freq]
        bucket.sort(reverse=True)
        if len(bucket) > cap:
            skipped += len(bucket) - cap
            bucket = bucket[:cap]
        for freq, word in bucket:
            if word in phrases:
                syllables, full, abbr = phrases[word]
            else:
                parts = [char_readings.get(ch) for ch in word]
                if any(p is None for p in parts):
                    continue
                syllables = [p[0] for p in parts]
                full = "".join(syllables)
                abbr = "".join(s[0] for s in syllables)
                composed += 1
            if length == 1:
                abbr = ""
            picked.append((word, full, abbr, freq, syllables))

    print(f"  选用 {len(picked)} 条（按配额丢弃 {skipped} 条低频词，"
          f"其中 {composed} 条为逐字拼接拼音）")

    rows = []
    for word, full, abbr, freq, syllables in picked:
        rows.append((word, full, abbr, freq))
        # 单字多音：同一字补录其它读音，使 长/行/重 这类字在任何读音下都能出来
        if len(word) == 1 and len(syllables) > 1:
            for alt in syllables[1:4]:
                rows.append((word, alt, "", max(freq // 4, 1)))
        if UMLAUT_ALIAS and "v" in full:
            rows.append((word, full.replace("v", "u"), abbr.replace("v", "u"), max(freq // 3, 1)))
    return rows


def write_db(rows, out: Path):
    out.parent.mkdir(parents=True, exist_ok=True)
    if out.exists():
        out.unlink()
    conn = sqlite3.connect(out)
    try:
        conn.executescript(
            """
            PRAGMA journal_mode = OFF;
            PRAGMA synchronous  = OFF;
            PRAGMA page_size    = 4096;
            CREATE TABLE word(
                text   TEXT NOT NULL,
                pinyin TEXT NOT NULL,
                abbr   TEXT NOT NULL,
                freq   INTEGER NOT NULL
            );
            """
        )
        conn.executemany("INSERT INTO word(text,pinyin,abbr,freq) VALUES(?,?,?,?)", rows)
        conn.executescript(
            """
            CREATE INDEX idx_pinyin ON word(pinyin);
            CREATE INDEX idx_abbr   ON word(abbr);
            """
        )
        conn.commit()
        conn.execute("ANALYZE")
        conn.execute("VACUUM")
        conn.commit()
    finally:
        conn.close()

    # 版本戳：应用启动时比对，不一致才重新拷贝 assets。
    # 没有它，APK 升级后用户手机上会一直用着旧词库。
    import hashlib
    digest = hashlib.sha1(out.read_bytes()).hexdigest()[:12]
    stamp = f"{digest}-{out.stat().st_size}"
    (out.parent / "dict.version").write_text(stamp, "utf8")
    print(f"  版本戳 dict.version = {stamp}")

    # MIT 要求分发包保留版权声明，随 APK 一起打进 assets
    (out.parent / "dict-NOTICE.txt").write_text(NOTICE, "utf8")


NOTICE = """\
dict.db — 本拼音词库由 scripts/build-dict.py 从以下 MIT 许可项目的数据构建。
本文件随应用一同分发，用于满足 MIT 许可证的版权声明要求。

本词库数据按 MIT 许可证分发；应用代码与词库数据相互独立。

1. pinyin-data — https://github.com/mozillazg/pinyin-data
   Copyright (c) 2016 mozillazg

2. python-pinyin — https://github.com/mozillazg/python-pinyin
   Copyright (c) 2014 mozillazg

3. jieba — https://github.com/fxsjy/jieba
   Copyright (c) 2013 Sun Junyi

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
"""


def prefix_range(q: str):
    """前缀检索的索引区间 [q, q + U+FFFF)。

    上界不能写成「末位字符 +1」：末位是 z 时加一得到 '{'，会跨过小写字母区，
    而 SQLite 默认按字节比较，大写字母排在所有小写字母之前，区间会瞬间失效。
    用 U+FFFF 作哨兵可覆盖任意后缀。
    """
    return q, q + "\uffff"


def self_check(out: Path):
    """跑几条真实输入的查询，确认索引与排序都符合预期。"""
    conn = sqlite3.connect(out)
    cases = ["nihao", "dianshi", "erweima", "zhongguo", "zg", "wifi",
             "lvse", "luse", "zhangda", "changda", "yaokongqi", "shurufa"]
    print("\n  自检（模拟 DictRepository 的前缀检索）")
    for q in cases:
        lo, hi = prefix_range(q)
        cur = conn.execute(
            "SELECT text FROM word WHERE pinyin >= ? AND pinyin < ? "
            "ORDER BY (pinyin = ?) DESC, freq DESC LIMIT 6",
            (lo, hi, q),
        )
        prefix = [r[0] for r in cur.fetchall()]
        cur = conn.execute(
            "SELECT text FROM word WHERE abbr = ? ORDER BY freq DESC LIMIT 3", (q,)
        )
        abbr = [r[0] for r in cur.fetchall()]
        print(f"  {q:10s} 全拼→ {'/'.join(prefix) or '—'}   简拼→ {'/'.join(abbr) or '—'}")
    total = conn.execute("SELECT count(*) FROM word").fetchone()[0]
    distinct = conn.execute("SELECT count(DISTINCT text) FROM word").fetchone()[0]
    conn.close()
    return total, distinct


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", type=Path, default=DEFAULT_OUT)
    ap.add_argument("--refresh", action="store_true", help="忽略缓存重新下载数据源")
    args = ap.parse_args()

    print("[1/4] 拉取数据源")
    jieba = fetch(JIEBA_URL, CACHE / "jieba_dict.txt", args.refresh)
    phrases_raw, chars_raw = fetch_pypinyin(args.refresh)

    print("[2/4] 解析")
    char_readings = load_chars(chars_raw)
    phrases = load_phrases(phrases_raw)
    words = load_words(jieba)
    print(f"  汉字 {len(char_readings)} 个读音 / 词组拼音 {len(phrases)} 条 / 候选词 {len(words)} 条")

    print("[3/4] 筛选入库")
    rows = build_rows(words, char_readings, phrases)
    write_db(rows, args.out)

    size = args.out.stat().st_size
    total, distinct = self_check(args.out)
    print(f"\n  输出 {args.out}")
    print(f"  记录 {total} 行 / 词条 {distinct} 个 / 体积 {size / 1024 / 1024:.2f} MB")
    return 0


if __name__ == "__main__":
    sys.exit(main())
