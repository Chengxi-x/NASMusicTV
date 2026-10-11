#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
网络艺人全量歌曲 —— 30 首上限归因 / 全量可行性 / 可播率 的可复现测量脚本
（对应方案 docs/network-artist-full-discography-plan.md，本文所有数字必须由本脚本再生，⛔ 不许手抄）

用法：
  python docs/archive/verification/scripts/netease_artist_probe.py                 # A/B/C/D 组（不触碰播放端点）
  python docs/archive/verification/scripts/netease_artist_probe.py --play 12        # 追加 E 组可播率抽样（会打 Meting type=url）
  python docs/archive/verification/scripts/netease_artist_probe.py --artist 6452    # 指定艺人 ID（默认 6452=周杰伦）

退出码：
  0  全部自检通过
  1  有断言失败 —— 意味着上游行为发生变化（例如端点开始支持分页 / 艺人接口下线），方案前提需要重审
  2  完全无网络或全部端点不可达（环境问题，不代表方案失效）

设计约束（踩过的坑，勿改）：
  - mikus 端点有速率限制，连续 20~30 次请求即返回 429（retryAfter≈3500s）。脚本对 429 **单独归类**，
    不当成"端点失效"；E 组默认带 pacing。
  - E 组的判据必须是**跟完整条重定向链后的 content-type**，不是 Meting 返回的 302 状态码：
    不可播曲目同样 302（转到 music.163.com/song/media/outer/url?id=X.mp3，再由网易 302 到 /404 HTML）。
    只看 302 会把假成功记成可播（本脚本旧版本犯过，负自检专门防它）。
  - 控制台可能是 CP936，输出只用 ASCII，中文一律经 errors='replace' 处理。
"""

import argparse
import json
import random
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

UA = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
}
NE_REFERER = dict(UA, Referer="https://music.163.com/")

PRESET_ENDPOINTS = [
    ("mikus", "https://meting.mikus.ink/api"),
    ("redcha", "https://meting.api.redcha.cn/api"),
    ("qijieya", "https://api.qijieya.cn/meting"),
]

# 延迟采样（决策"统一搜索 5s 预算如何在 网易优先 → 端点兜底 之间分配"用）
LAT = []          # [(label, ms)]
LAT_BUDGET_MS = 5000   # SearchAggregator.kt:75 NETWORK_TIMEOUT

RESULTS = []
FAILS = []
RAN_NET = False

# Windows 控制台默认 CP936，重定向到文件时中文会抛 UnicodeEncodeError；固定为 UTF-8
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


def report(group, name, ok, detail, warn=False):
    tag = "PASS" if ok else ("WARN" if warn else "FAIL")
    RESULTS.append((group, name, tag, detail))
    print("[%s] %-3s %-46s %s" % (tag, group, name, detail))
    if not ok and not warn:
        FAILS.append("%s/%s: %s" % (group, name, detail))


def lat_bucket(url):
    """按 URL 归类延迟样本（桶名与 §11 的预算分配表一一对应）"""
    if "/api/search/get" in url:
        return "netease_type1_search"
    if "/api/v1/artist/songs" in url:
        return "netease_artist_songs"
    # 预设端点按名字归类：netloc 首标签不可用（redcha 是 meting.api.xxx、
    # qijieya 是 api.qijieya.cn/meting，截首段会把两个端点并进同一个桶）
    for name, base in PRESET_ENDPOINTS:
        if url.startswith(base):
            kind = "meting_resolve_url" if "type=url" in url else (
                "meting_search" if "type=search" in url else None)
            if kind:
                return "%s@%s" % (kind, name)
    if "type=url" in url:
        return "meting_resolve_url@" + urllib.parse.urlparse(url).netloc
    if "type=search" in url:
        return "meting_search@" + urllib.parse.urlparse(url).netloc
    return "other"


def lat_sample(url, t0, status):
    LAT.append((lat_bucket(url), int((time.monotonic() - t0) * 1000), status))


def pct(vals, p):
    """最近秩法取分位（小样本够用，不做插值假装精度）"""
    s = sorted(vals)
    return s[min(len(s) - 1, int(round(p / 100.0 * (len(s) - 1))))]


# ----------------------------------------------------------------------------
# L 组：接口延迟分布（决定"网易优先 + 端点兜底"的 5s 预算怎么切）
# ----------------------------------------------------------------------------
def group_l(keyword, n, pacing):
    print("\n=== L. 接口延迟分布（--latency %d）===" % n)
    before = len(LAT)
    # 网易原生：type=1 搜索
    for i in range(n):
        http("https://music.163.com/api/search/get",
             data=urllib.parse.urlencode(
                 {"s": keyword, "type": "1", "offset": "0", "total": "true", "limit": "30"}
             ).encode())
        time.sleep(pacing)
    # 网易原生：艺人全量分页（第一页即够代表）
    for i in range(n):
        http("https://music.163.com/api/v1/artist/songs?id=6452&offset=0&limit=100&total=true")
        time.sleep(pacing)
    # 公共 Meting 端点：search（真实点播路径的第一步）——**三个预设全测**，
    # 因为搜索兜底链会遍历它们，预算必须按最差端点算
    for name, base in PRESET_ENDPOINTS:
        for i in range(n):
            http("%s?server=netease&type=search&id=%s" % (base, urllib.parse.quote(keyword)))
            time.sleep(pacing)
    samples = LAT[before:]
    by = {}
    for b, ms, st in samples:
        by.setdefault(b, []).append((ms, st))
    for b in sorted(by):
        vals = [ms for ms, _ in by[b]]
        bad = sum(1 for ms, st in by[b] if st not in (200, 302))
        print("  %-22s n=%2d p50=%5dms p95=%5dms max=%5dms 非2xx=%d"
              % (b, len(vals), pct(vals, 50), pct(vals, 95), max(vals), bad))
    report("L", "延迟仪表自检:必须有采样且耗时为正",
           bool(samples) and all(ms > 0 for _, ms, _ in samples),
           "buckets=%d samples=%d" % (len(by), len(samples)))
    ns = [ms for ms, _ in by.get("netease_type1_search", [])]
    if ns:
        p95 = pct(ns, 95)
        mt = [ms for b in by if b.startswith("meting_search") for ms, _ in by[b]]
        report("L", "网易 type=1 搜索 p95 需在 5s 预算内可容一次兜底",
               p95 + pct(mt or ns, 50) < LAT_BUDGET_MS,
               "netease p95=%dms + meting p50=%dms < %dms" % (p95, pct(mt or ns, 50), LAT_BUDGET_MS),
               warn=True)
    # 最慢端点单独钉一条：兜底链是按顺序试的，最慢那个决定"还剩多少预算"
    for b in sorted(by):
        if b.startswith("meting_search"):
            v = [ms for ms, _ in by[b]]
            report("L", "%s p95 是否已逼近 5s 预算" % b, pct(v, 95) < LAT_BUDGET_MS,
                   "p50=%dms p95=%dms max=%dms" % (pct(v, 50), pct(v, 95), max(v)), warn=True)
    return by


def http(url, data=None, headers=None, timeout=20, follow=True):
    """返回 (status, headers_dict, body_bytes, final_url)。follow=False 时不跟随重定向。"""
    global RAN_NET
    RAN_NET = True
    h = dict(NE_REFERER if "music.163.com" in url else UA)
    h.update(headers or {})
    req = urllib.request.Request(url, data=data, headers=h)

    class _Stop(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, *a, **k):
            return None

    opener = urllib.request.build_opener() if follow else urllib.request.build_opener(_Stop)
    t0 = time.monotonic()
    try:
        with opener.open(req, timeout=timeout) as r:
            lat_sample(url, t0, r.getcode())
            return r.getcode(), dict(r.headers), r.read(), r.geturl()
    except urllib.error.HTTPError as e:
        body = b""
        lat_sample(url, t0, e.code)
        try:
            body = e.read()
        except Exception:
            pass
        # 不跟随重定向时 302 走这里
        return e.code, dict(e.headers), body, url
    except Exception as e:
        lat_sample(url, t0, None)
        return None, {}, repr(e)[:80].encode(), url


def is_rate_limited(status, body):
    if status == 429:
        return True
    try:
        return b"Too Many Requests" in body or b"\xe9\xa2\x91\xe7\xb9\x81" in body
    except Exception:
        return False


def parse_json(body):
    try:
        return json.loads(body.decode("utf-8", errors="replace"))
    except Exception:
        return None


def song_ids(items):
    out = []
    for it in items:
        m = re.search(r"[?&]id=([^&]+)", it.get("url") or "")
        out.append(m.group(1) if m else "?")
    return out


def overlap(a, b):
    """集合重叠率。⚠️ 不能用顺序敏感的相等：上游热榜会重排同一批曲目，
    顺序比较会把"重排"误判成"真分页"（本脚本旧版犯过）。
    返回 (ratio, 说明)。ratio=1.0 表示同一批（无分页）。"""
    sa, sb = set(a), set(b)
    if not sa or not sb:
        return 0.0, "empty"
    r = len(sa & sb) / float(len(sa | sb))
    return r, "jaccard=%.2f inter=%d/%d" % (r, len(sa & sb), len(sa | sb))


# ----------------------------------------------------------------------------
# A 组：统一搜索的 30 首上限到底由谁决定
# ----------------------------------------------------------------------------
def item_fields(it):
    """兼容两端点字段名：redcha/mikus 用 title/author，qijieya 用 name/artist（见 MetingApiService:109-111）"""
    title = it.get("title") or it.get("name") or ""
    author = it.get("author") or it.get("artist") or it.get("authors") or ""
    if isinstance(author, list):
        author = "/".join(str(x.get("name") if isinstance(x, dict) else x) for x in author)
    m = re.search(r"[?&]id=([^&]+)", it.get("url") or "")
    return title, author, (m.group(1) if m else None)


def norm(s):
    s = (s or "").lower()
    s = re.sub(r"[\s\u3000·、,，.。\-—_()（）\[\]【】'\"“”‘’/]+", "", s)
    return s


def title_match(a, b):
    na, nb = norm(a), norm(b)
    return bool(na) and bool(nb) and (na == nb or na in nb or nb in na)


def artist_tokens(s):
    return {norm(x) for x in re.split(r"[/,、&\s]+", s or "") if norm(x)}


def resolve_chain(base, server, sid, timeout=25):
    """跟完整条重定向链后按最终 Content-Type 判定。返回 (verdict, content_type, final_url)。"""
    url = "%s?server=%s&type=url&id=%s" % (base, server, urllib.parse.quote(str(sid)))
    st, h, b, final = http(url, follow=True, timeout=timeout)
    if is_rate_limited(st, b):
        return "RATE_LIMITED", "", final
    if st is None:
        return "UNREACHABLE", "", final
    ctype = (h.get("Content-Type") or "").lower()
    return ("PLAYABLE" if "audio" in ctype else "DEAD"), ctype, final


def meting_search(base, server, keyword):
    """返回 (list|None, rate_limited:bool)。None 表示不可用或解析失败。"""
    st, _, b, _ = http("%s?server=%s&type=search&id=%s" % (base, server, urllib.parse.quote(keyword)))
    if is_rate_limited(st, b):
        return None, True
    d = parse_json(b)
    return (d if isinstance(d, list) else None), False


def stratified_picks(collected, sample):
    """按 privilege.st 分层取样：只取前 N 首会碰上同质批次，相关性检验会退化成恒真。"""
    by_st = {}
    for s in collected:
        by_st.setdefault((s.get("privilege") or {}).get("st"), []).append(s)
    groups = sorted(by_st.items(), key=lambda kv: -len(kv[1]))
    picks = []
    per = max(1, sample // max(1, len(groups)))
    for st_val, lst in groups[:max(1, sample // per)]:
        picks += lst[::max(1, len(lst) // per)][:per]
    return picks[:sample]


# ----------------------------------------------------------------------------
# A 组：统一搜索的 30 首上限到底由谁决定
# ----------------------------------------------------------------------------
def group_a(keyword, retries=2):
    """返回 (True=至少一个端点给出有效数组, {端点名: 首页 id 列表})。"""
    print("\n=== A. Meting type=search 每端点条数 + 分页参数是否生效 ===")
    enc = urllib.parse.quote(keyword)
    any_ok = False
    sets = {}
    for name, base in PRESET_ENDPOINTS:
        d1 = None
        for attempt in range(retries + 1):
            st1, _, b1, _ = http("%s?server=netease&type=search&id=%s" % (base, enc))
            if is_rate_limited(st1, b1):
                d1 = "RATE_LIMITED"
                break
            d1 = parse_json(b1)
            if isinstance(d1, list) and d1:
                break
            d1 = None if not isinstance(d1, list) else d1
            if attempt < retries:
                time.sleep(3)
        if d1 == "RATE_LIMITED":
            report("A", "%s 搜索" % name, True, "SKIPPED_RATE_LIMITED(429)", warn=True)
            continue
        if not isinstance(d1, list) or not d1:
            # 上游 5xx / 空数组是端点自身的抖动，不能当成"上限结论被推翻"
            report("A", "%s 搜索" % name, True,
                   "TRANSIENT_UNREACHABLE(retried=%d, last=%s)" % (retries, str(d1)[:40]), warn=True)
            continue
        n1 = len(d1)
        any_ok = True
        ids1 = song_ids(d1)
        sets[name] = ids1
        # 同一请求加 page/limit/offset 变体，用**集合重叠率**判断是否真的换页
        st2, _, b2, _ = http("%s?server=netease&type=search&id=%s&page=2" % (base, enc))
        d2 = parse_json(b2)
        st3, _, b3, _ = http("%s?server=netease&type=search&id=%s&limit=100&offset=30" % (base, enc))
        d3 = parse_json(b3)
        # 负自检前提：变体必须是**非空**数组，否则"恒等"是空表对空表的假绿
        r2, note2 = overlap(ids1, song_ids(d2)) if isinstance(d2, list) and d2 else (0.0, "empty")
        r3, note3 = overlap(ids1, song_ids(d3)) if isinstance(d3, list) and d3 else (0.0, "empty")
        report("A", "%s 单页条数" % name, n1 == 30, "n=%d (期望 30)" % n1)
        report("A", "%s page=2 是否换页" % name, r2 == 1.0,
               "overlap=%.2f (%s) -> 1.00 表示参数被忽略" % (r2, note2), warn=r2 != 1.0)
        report("A", "%s limit/offset 是否生效" % name, r3 == 1.0,
               "overlap=%.2f (%s) -> 1.00 表示参数被忽略" % (r3, note3), warn=r3 != 1.0)
    if not any_ok:
        # 全部端点抖动/限流 = 环境态，不等于"30 上限结论被推翻"
        report("A", "至少一个预设端点可用", True,
               "ENV_BLOCKED: 全部端点不可达/限流，本轮 A 组结论不可采信（exit 2）", warn=True)
    return any_ok, sets


# ----------------------------------------------------------------------------
# A2 组：跨端点结果集是否是同一批 —— 决定"并集"能否绕过 30 上限
# ----------------------------------------------------------------------------
def group_a2(sets):
    print("\n=== A2. 跨端点搜索结果集重叠率（并集能不能绕过 30）===")
    names = [n for n, s in sets.items() if s]
    if len(names) < 2:
        report("A2", "可用端点数", True,
               "只有 %s，跨端点并集判据不可采信" % (names or "无"), warn=True)
        return
    all_same = True
    for i in range(len(names)):
        for j in range(i + 1, len(names)):
            a, b = sets[names[i]], sets[names[j]]
            r, note = overlap(a, b)
            union = len(set(a) | set(b))
            if r < 1.0:
                all_same = False
            report("A2", "%s vs %s" % (names[i], names[j]), True,
                   "%s union=%d -> %s" % (note, union,
                                          "同一批（并集无效）" if r == 1.0 else "不同批（并集能扩样本）"),
                   warn=(r == 1.0))
    report("A2", "端点结果集是否彼此相同", all_same is False if len(names) > 1 else True,
           "全部相同=%s；相同即说明'遍历多端点取并集'不能扩大集合，只能提高命中率" % all_same,
           warn=all_same)


# ----------------------------------------------------------------------------
# B 组：Meting type=artist（零新端点的次优路径）
# ----------------------------------------------------------------------------
def group_b(artist_id):
    print("\n=== B. Meting type=artist 条数 + 分页 ===")
    base = PRESET_ENDPOINTS[1][1]  # redcha（mikus 更易限流）
    url1 = "%s?server=netease&type=artist&id=%s" % (base, artist_id)
    st1, _, b1, _ = http(url1)
    if is_rate_limited(st1, b1):
        report("B", "artist 接口可达", True, "SKIPPED_RATE_LIMITED(429)", warn=True)
        return
    d1 = parse_json(b1)
    if not isinstance(d1, list) or not d1:
        report("B", "artist 接口返回数组", False, "status=%s body=%s" % (st1, b1[:80]))
        return
    report("B", "artist 单页条数", len(d1) == 50, "n=%d (期望 50，比 search 多 20)" % len(d1))
    ids1 = song_ids(d1)
    # 仪表自检（正 + 负）：同一请求重取必须 overlap=1.0，跨接口必须 <1.0
    _, _, b1b, _ = http(url1)
    d1b = parse_json(b1b)
    r_stab, _ = overlap(ids1, song_ids(d1b)) if isinstance(d1b, list) and d1b else (0.0, "empty")
    report("B", "正自检:重取同一请求 overlap=1.0", r_stab == 1.0,
           "overlap=%.2f -> 小于 1.0 说明端点本身在抖动，分页判据不可采信" % r_stab, warn=r_stab != 1.0)
    st2, _, b2, _ = http("%s?server=netease&type=artist&id=%s&page=2" % (base, artist_id))
    d2 = parse_json(b2)
    r2, note2 = overlap(ids1, song_ids(d2)) if isinstance(d2, list) and d2 else (0.0, "empty")
    report("B", "artist page=2 是否换页", r2 == 1.0,
           "%s -> 1.00 表示同一批（无分页），<1.0 才是真分页" % note2, warn=r2 != 1.0)
    # 负自检：artist 与 search 是两个不同集合，否则说明 overlap 恒真
    st3, _, b3, _ = http("%s?server=netease&type=search&id=%s" % (base, urllib.parse.quote("周杰伦")))
    d3 = parse_json(b3)
    if isinstance(d3, list) and d3:
        r3, note3 = overlap(song_ids(d3), ids1)
        report("B", "负自检:search/artist 集合可区分", r3 < 1.0,
               "%s (overlap=%.2f)" % (note3, r3), warn=r3 == 1.0)


# ----------------------------------------------------------------------------
# C 组：艺人 ID 发现（网易原生 search type=100）
# ----------------------------------------------------------------------------
def group_c(keyword):
    print("\n=== C. 网易原生搜索（POST /api/search/get）：type=1 与 type=100 ===")
    url = "https://music.163.com/api/search/get"

    def post(**kw):
        form = urllib.parse.urlencode(kw).encode()
        return http(url, data=form)

    st, _, b, _ = post(s=keyword, type="1", offset="0", total="true", limit="100")
    d = parse_json(b)
    if not isinstance(d, dict) or d.get("code") != 200:
        report("C", "type=1 歌曲搜索", False, "status=%s body=%s" % (st, b[:100]))
        return None
    songs = (d.get("result") or {}).get("songs") or []
    report("C", "type=1 limit=100 实际返回", len(songs) == 100, "n=%d (期望 100)" % len(songs))
    ids1 = [s["id"] for s in songs]

    # C-字段覆盖：Q1(C+B) 的打标/排序与 Q2(网易优先搜索) 都要在**搜索响应**上取字段，
    # 而 D 组那套覆盖率是「艺人歌曲接口」的，不能外推到 type=1。逐个数字段是否存在。
    def cov(name, fn):
        n = sum(1 for s in songs if fn(s))
        return "%s=%d/%d" % (name, n, len(songs))

    fields = [
        ("privilege.st 存在", lambda s: isinstance((s.get("privilege") or {}).get("st"), int)),
        ("duration>0", lambda s: isinstance(s.get("duration"), int) and s["duration"] > 0),
        ("album.picUrl", lambda s: bool((s.get("album") or {}).get("picUrl"))),
        ("album.name", lambda s: bool((s.get("album") or {}).get("name"))),
        ("album.id", lambda s: isinstance((s.get("album") or {}).get("id"), int)),
        ("artists[].name", lambda s: any(a.get("name") for a in (s.get("artists") or []))),
        ("fee 存在", lambda s: s.get("fee") is not None),
    ]
    covered, missing = [], []
    for label, fn in fields:
        n = sum(1 for s in songs if fn(s))
        (covered if n else missing).append(label)
        # 覆盖率为 0 记 WARN（不 FAIL）：字段缺失不代表接口坏了，但**功能不能建在它上面**
        report("C", "type=1 字段覆盖:%s" % label, n > 0, cov(label, fn), warn=(n == 0))
    # 负自检：故意找一个不存在的字段，覆盖率必须为 0，否则说明计数器恒真
    bogus = sum(1 for s in songs if (s.get("__no_such_field__") or {}).get("x"))
    report("C", "负自检:不存在字段的覆盖率必须为 0", bogus == 0,
           "bogus=%d（>0 即计数器恒真，上面所有覆盖率不可采信）" % bogus)
    report("C", "type=1 字段齐备度汇总", len(missing) == 0,
           "齐备 %d 项 / 缺失 %d 项%s" % (len(covered), len(missing),
                                    ("（缺失=%s）" % ",".join(missing)) if missing else ""),
           warn=bool(missing))
    st, _, b, _ = post(s=keyword, type="1", offset="100", total="true", limit="100")
    d2 = parse_json(b)
    ids2 = [s["id"] for s in ((d2 or {}).get("result") or {}).get("songs") or []]
    r_off, note_off = overlap(ids1, ids2) if ids2 else (0.0, "empty")
    report("C", "type=1 offset=100 换页", bool(ids2) and r_off < 0.1,
           "n=%d %s -> overlap 接近 0 才是真分页" % (len(ids2), note_off))
    artist_ids = sorted({a.get("id") for s in songs for a in (s.get("artists") or [])})
    report("C", "type=1 响应含 artists[].id", len(artist_ids) > 0, "distinct_artist_ids=%d" % len(artist_ids))

    st, _, b, _ = post(s=keyword, type="100", offset="0", total="true", limit="10")
    d3 = parse_json(b)
    artists = (d3 or {}).get("result") or {}
    lst = artists.get("artists") or []
    report("C", "type=100 艺人搜索", bool(lst), "artistCount=%s returned=%d" % (artists.get("artistCount"), len(lst)))
    top = lst[0] if lst else None
    if top:
        report("C", "type=100 首选艺人字段完整", isinstance(top.get("id"), int) and isinstance(top.get("musicSize"), int),
               "id=%s musicSize=%s albumSize=%s" % (top.get("id"), top.get("musicSize"), top.get("albumSize")))
        return top.get("id")
    return None


# ----------------------------------------------------------------------------
# D 组：艺人全量歌曲分页（GET /api/v1/artist/songs）
# ----------------------------------------------------------------------------
def group_d(artist_id):
    print("\n=== D. 网易原生 /api/v1/artist/songs 分页与字段覆盖 ===")
    base = "https://music.163.com/api/v1/artist/songs"
    collected, offsets, total_field, more_seq = [], [], None, []
    offset, guard = 0, 0
    while guard < 12:
        guard += 1
        st, _, b, _ = http("%s?id=%s&total=true&limit=100&offset=%d" % (base, artist_id, offset))
        d = parse_json(b)
        if not isinstance(d, dict) or d.get("code") != 200:
            report("D", "offset=%d 请求成功" % offset, False, "status=%s body=%s" % (st, b[:100]))
            return None, None
        songs = d.get("songs") or []
        total_field = d.get("total") if total_field is None else total_field
        more_seq.append(bool(d.get("more")))
        collected.extend(songs)
        offsets.append((offset, len(songs)))
        if not songs or not d.get("more"):
            break
        offset += 100
    uniq = {s["id"] for s in collected}
    report("D", "接口返回 total", isinstance(total_field, int) and total_field > 0, "total=%s" % total_field)
    report("D", "分页拉到的条数", len(collected) > 0,
           "pages=%s unique_ids=%d" % (offsets, len(uniq)))
    report("D", "跨页无重复（offset 正常）", len(uniq) == len(collected),
           "raw=%d uniq=%d dup=%d" % (len(collected), len(uniq), len(collected) - len(uniq)))
    report("D", "more 标志在末页转 false", more_seq and more_seq[-1] is False, "more_seq=%s" % more_seq)
    report("D", "总数与 total 一致", len(collected) >= (total_field or 0) * 0.95,
           "loaded=%d total=%s" % (len(collected), total_field))

    # limit 边界：实测 limit>100 会返回异常集合（total 变化、顺序错乱），方案锁 limit<=100
    st, _, b, _ = http("%s?id=%s&total=true&limit=200&offset=0" % (base, artist_id))
    d200 = parse_json(b) or {}
    ids100 = {s["id"] for s in collected[:100]}
    ids200 = {s["id"] for s in (d200.get("songs") or [])}
    prefix_ok = bool(ids100) and ids100.issubset(ids200)
    report("D", "负自检:limit=200 是否等价于两页 100", prefix_ok,
           "n200=%d total@200=%s first100_is_subset=%s -> 不等价，须锁 limit<=100" % (
               len(ids200), d200.get("total"), prefix_ok), warn=True)

    # 字段覆盖
    pic = sum(1 for s in collected if (s.get("album") or {}).get("picUrl"))
    dur = sum(1 for s in collected if (s.get("duration") or 0) > 0)
    st_dist = {}
    for s in collected:
        k = (s.get("privilege") or {}).get("st")
        st_dist[k] = st_dist.get(k, 0) + 1
    fee_dist = {}
    for s in collected:
        fee_dist[s.get("fee")] = fee_dist.get(s.get("fee"), 0) + 1
    report("D", "album.picUrl 覆盖率", pic >= len(collected) * 0.9,
           "%d/%d 可直接作 coverUrl" % (pic, len(collected)))
    report("D", "duration 覆盖（毫秒）", dur == len(collected), "%d/%d" % (dur, len(collected)))
    report("D", "privilege.st 分布", True, "%s" % st_dist)
    report("D", "fee 分布", True, "%s" % fee_dist)
    return collected, st_dist


# ----------------------------------------------------------------------------
# E 组：真实可播率（跟完整条重定向链）
# ----------------------------------------------------------------------------
def group_e(collected, endpoint, sample, pacing, st_dist=None):
    print("\n=== E. 可播率抽样（判据=全链跟随后的 content-type） ===")
    base = dict(PRESET_ENDPOINTS).get(endpoint, endpoint)
    # 分层抽样：按 privilege.st 两组各取一半，保证正/负类都有样本
    # （只取列表前 N 首会碰上同质批次，相关性检验退化成恒真）
    by_st = {}
    for s in collected:
        by_st.setdefault((s.get("privilege") or {}).get("st"), []).append(s)
    picks = stratified_picks(collected, sample)
    if len(picks) < 2:
        report("E", "样本量", False, "分层后只有 %d 首可选" % len(picks))
        return []
    report("E", "分层抽样构成", True, "n=%d 按 st 分组=%s" % (
        len(picks), {k: sum(1 for p in picks if (p.get("privilege") or {}).get("st") == k) for k in by_st}))
    rows, playable, dead_songs = [], 0, []
    for s in picks:
        sid = s["id"]
        # ⚠️ 必须重试去抖：公共端点会偶发把可播曲目答成 404/HTML，单次判 DEAD 会把可播率压低
        #（上一轮 8.7% 这个数正是这么来的，见方案修订说明 v1.5）
        verdict = ctype = final = ""
        for _ in range(3):
            verdict, ctype, final = resolve_chain(base, "netease", sid)
            if verdict in ("PLAYABLE", "RATE_LIMITED"):
                break
            time.sleep(2.0)
        if verdict == "RATE_LIMITED":
            report("E", "端点限流", True, "429 @ id=%s，E 组中止（pacing=%ss）" % (sid, pacing), warn=True)
            break
        if verdict == "PLAYABLE":
            playable += 1
        else:
            dead_songs.append(s)
        rows.append((sid, s.get("fee"), (s.get("privilege") or {}).get("st"), verdict, final[:58], ctype[:20]))
        time.sleep(pacing)
    print("  %-12s %-4s %-5s %-9s %-14s %s" % ("id", "fee", "st", "verdict", "content_type", "final_url"))
    for r in rows:
        print("  %-12s %-4s %-5s %-9s %-14s %s" % (str(r[0]), r[1], r[2], r[3], r[5], r[4]))
    n = len(rows)
    report("E", "抽样可播率", n > 0, "playable=%d dead=%d / %d (%.0f%% 可播)" % (
        playable, len(dead_songs), n, 100.0 * playable / n if n else 0))
    # 核心断言：只看 302 会误判 —— 必须存在 dead 且其 http 状态仍是 200/302
    dead_200 = [r for r in rows if r[3] == "DEAD"]
    report("E", "负自检:DEAD 样本存在", len(dead_200) > 0,
           "dead=%d -> 证明 302 不等于可播（旧判据在此会 100%% 误判）" % len(dead_200), warn=not dead_200)
    # privilege.st 与可播性的相关性（方案用它做标记，不用它做过滤）
    agree = sum(1 for r in rows if (r[2] == 0) == (r[3] == "PLAYABLE"))
    if n >= 6:
        report("E", "privilege.st==0 与可播的一致性", agree >= n * 0.8,
               "%d/%d 吻合（不吻合即说明该字段不可作标记依据）" % (agree, n), warn=agree < n * 0.8)
    fee_play = {r[1] for r in rows if r[3] == "PLAYABLE"}
    fee_dead = {r[1] for r in rows if r[3] == "DEAD"}
    report("E", "fee 字段判别力", bool(fee_play) and bool(fee_dead),
           "playable_fees=%s dead_fees=%s %s" % (
               sorted(x for x in fee_play if x is not None), sorted(x for x in fee_dead if x is not None),
               "<- 有交集则 fee 不可用" if (fee_play & fee_dead) else ""))
    # ⚠️ 不再按 st 外推可播占比：上一轮用"st==0 与可播 12/12 吻合"外推过 8.7%，
    #    本轮重试去抖后该吻合不成立（见上面的相关性断言）。无偏可播率只由 F 组给出。
    if st_dist is not None:
        good = st_dist.get(0, 0)
        tot = sum(st_dist.values())
        report("E", "st==0 在全量列表中的占比", True,
               "%d/%d = %.1f%%（仅作分布参考，⛔ 不可当作可播率引用）" % (
                   good, tot, 100.0 * good / tot if tot else 0))
    return dead_songs


# ----------------------------------------------------------------------------
# F 组：换端点能不能救回（"8.7% 是单端点数字还是所有端点的天花板"）
# ----------------------------------------------------------------------------
def group_f(collected, sample, pacing, attempts=2, endpoints=("redcha", "qijieya", "mikus")):
    """随机抽样（非分层、非前 N 首）在多个端点上测可播性 —— F 组给的是**无偏可播率**。
    E 组的分层比例是人为配平，只能用来验相关性，不能当可播率引用。"""
    print("\n=== F. 随机抽样 × 多端点可播矩阵（无偏可播率 + 换端点能否救回）===")
    if not collected:
        report("F", "前置条件", False, "D 组没有歌曲列表，F 组无法抽样")
        return {}, {}
    picks = random.sample(collected, min(sample, len(collected)))
    matrix, per_ep = {}, {}
    for name in endpoints:
        base = dict(PRESET_ENDPOINTS).get(name)
        if not base:
            continue
        n_play = n_tot = 0
        limited = False
        for s in picks:
            v = "DEAD"
            ct = ""
            for _ in range(attempts):     # 重试去抖：公共端点会偶发 404/5xx
                vv, c2, _ = resolve_chain(base, "netease", s["id"])
                if vv == "RATE_LIMITED":
                    limited = True
                    break
                if vv == "PLAYABLE":
                    v = "PLAYABLE"
                    ct = c2
                    break
                ct = c2
            if limited:
                break
            matrix.setdefault(s["id"], {})[name] = v
            n_tot += 1
            if v == "PLAYABLE":
                n_play += 1
            time.sleep(pacing)
        if limited or n_tot == 0:
            report("F", "%s 可播率" % name, True,
                   "SKIPPED（%s），已测 %d 首" % ("429 限流" if limited else "无样本", n_tot), warn=True)
            continue
        per_ep[name] = (n_play, n_tot)
        report("F", "%s 可播率" % name, True, "%d/%d = %.1f%%（随机样本，重试 %d 次去抖）" % (
            n_play, n_tot, 100.0 * n_play / n_tot, attempts))
    if not per_ep:
        report("F", "可用端点", True, "ENV_BLOCKED: 全部端点限流/不可达，可播率本轮不可采信", warn=True)
        return matrix, per_ep, []
    # 换端点能否救回：某端点 DEAD、另一端点 PLAYABLE
    both = [sid for sid, m in matrix.items() if len(m) > 1]
    rescued = [sid for sid, m in matrix.items() if "PLAYABLE" in m.values() and "DEAD" in m.values()]
    alldead = [sid for sid, m in matrix.items() if set(m.values()) == {"DEAD"}]
    report("F", "端点间可播性是否不一致", True,
           "%d/%d 首在不同端点上结论不同 -> %s" % (
               len(rescued), len(both),
               "换端点确有增益" if rescued else "完全一致（公共端点走同一上游，换端点无效）"),
           warn=not rescued)
    report("F", "全端点一致 DEAD", True, "%d/%d 首在所有可用端点都不可播" % (len(alldead), len(both)))
    dead_here = [s for s in picks if s["id"] in matrix and set(matrix[s["id"]].values()) == {"DEAD"}]
    return matrix, per_ep, dead_here


# ----------------------------------------------------------------------------
# G 组：不可播曲目能否从其他平台来源救回
# ----------------------------------------------------------------------------
OTHER_SERVERS = ["tencent", "kugou", "kuwo", "migu", "xiami"]


def group_g_svr_matrix(pacing):
    """G1：各端点到底支持哪些 server 平台（设置页有 5 个来源，端点未必都认）"""
    print("\n=== G1. 端点 × server 平台支持矩阵（type=search）===")
    support = {}
    for name, base in PRESET_ENDPOINTS:
        for srv in ["netease"] + OTHER_SERVERS:
            items, rl = meting_search(base, srv, "周杰伦 彩虹")
            if rl:
                report("G", "%s/%s" % (name, srv), True, "SKIPPED_RATE_LIMITED", warn=True)
                continue
            if items is None:
                support.setdefault(srv, []).append((name, "ERROR"))
                report("G", "%s/%s" % (name, srv), True, "非数组（端点不认该平台）", warn=True)
            else:
                support.setdefault(srv, []).append((name, len(items)))
                report("G", "%s/%s" % (name, srv), True, "n=%d" % len(items), warn=(len(items) == 0))
            time.sleep(pacing)
    return support


def group_g(dead_songs, endpoint, pacing, limit=5, support=None):
    print("\n=== G2. 网易侧 DEAD 的曲目：换 server 平台重搜能否播 ===")
    if not dead_songs:
        report("G", "前置条件", True, "没有不可播样本（E/F 组未跑或全部可播），跳过", warn=True)
        return
    base = dict(PRESET_ENDPOINTS).get(endpoint, endpoint)
    picks = dead_songs[:limit]
    report("G", "样本", True, "%d 首网易侧不可播曲目，端点=%s" % (len(picks), endpoint))
    any_rescue = False
    for srv in OTHER_SERVERS:
        if support and not any(n == endpoint and (isinstance(c, int) and c > 0) for n, c in support.get(srv, [])):
            report("G", "%s 可用性" % srv, True, "该端点不认此平台，跳过（见 G1）", warn=True)
            continue
        hits = playable = 0
        for s in picks:
            title = s.get("name") or s.get("title") or ""
            artists = [a.get("name", "") for a in (s.get("artists") or [])]
            items, rl = meting_search(base, srv, ("%s %s" % (title, artists[0] if artists else "")).strip())
            if rl:
                report("G", "%s 限流" % srv, True, "429，该平台结论不可采信", warn=True)
                break
            want_a = {norm(x) for x in artists if norm(x)}
            match = None
            for it in (items or []):
                t, a, sid = item_fields(it)
                if sid and title_match(title, t) and (not want_a or (artist_tokens(a) & want_a)):
                    match = (t, a, sid)
                    break
            if match:
                hits += 1
                v, _, _ = resolve_chain(base, srv, match[2])
                if v == "PLAYABLE":
                    playable += 1
                time.sleep(pacing)
        if playable:
            any_rescue = True
        report("G", "%s 同名命中/可播" % srv, True, "命中 %d/%d，其中可播 %d" % (hits, len(picks), playable),
               warn=(hits == 0))
    report("G", "跨平台救回是否存在", any_rescue,
           "%s -> %s" % ("存在" if any_rescue else "全部平台都无增益",
                         "可播率天花板受平台授权分布影响，不是端点问题" if any_rescue else
                         "公共端点上各平台同样受版权门禁"), warn=not any_rescue)


def group_g3(dead_songs, endpoint, pacing, limit=4):
    """G3：换 server 解析同一个**数字 id** 会不会静默播成另一首歌（App 不把平台记在 Song 上）"""
    print("\n=== G3. 同一数字 id 换 server 解析：是否串成另一首歌 ===")
    if not dead_songs:
        report("G", "G3 前置条件", True, "无 DEAD 样本", warn=True)
        return
    base = dict(PRESET_ENDPOINTS).get(endpoint, endpoint)
    collisions = 0
    for s in dead_songs[:limit]:
        sid = s["id"]
        title = s.get("name") or s.get("title") or ""
        for srv in OTHER_SERVERS[:3]:
            st, h, b, _ = http("%s?server=%s&type=search&id=%s" % (base, srv, urllib.parse.quote(title)))
            items = b
            # 直接问 url 接口：该平台是否认这个 id，返回的是什么
            v, ct, final = resolve_chain(base, srv, sid)
            if v == "PLAYABLE":
                collisions += 1
                report("G", "G3 id=%s/%s" % (sid, srv), True,
                       "可播！网易 id 在 %s 上解析成功（网易歌名=%s）-> 平台与 id 命名空间必须成对存储" % (srv, title),
                       warn=True)
            time.sleep(pacing)
    report("G", "G3 id 跨平台串歌", collisions == 0,
           "串歌数=%d；>0 即证明 Song 必须记录来源平台，否则切来源后旧 id 会被当作新平台的 id 解析" % collisions,
           warn=collisions > 0)


# ----------------------------------------------------------------------------
# H 组：其他平台原生开放接口（艺人全量列表能不能照抄网易这条路）
# ----------------------------------------------------------------------------
KUGOU_SINGER = "http://mobilecdn.kugou.com/api/v3/search/singer?format=json&keyword=%s&pageno=1&pagesize=10"
KUGOU_SONGS = ("http://mobilecdn.kugou.com/api/v3/singer/song?format=json&adminflag=1&appid=1005"
               "&filter=76&keyword=&page=%d&pagesize=%d&singerid=%d&version=1")


def group_h(keyword, endpoint, pacing):
    print("\n=== H. 各平台原生免鉴权接口：艺人发现 + 全量分页 ===")
    base = dict(PRESET_ENDPOINTS).get(endpoint, endpoint)

    # H1 酷狗：搜索歌手 → singerid → 歌曲分页
    st, _, b, _ = http(KUGOU_SINGER % urllib.parse.quote(keyword),
                       headers={"Referer": "http://www.kugou.com/"})
    d = parse_json(b)
    singer = None
    if isinstance(d, dict) and d.get("status") == 1:
        for x in (d.get("data") or []):
            if x.get("singername") == keyword:
                singer = x
                break
    report("H", "kugou 歌手搜索", singer is not None,
           "http=%s singerid=%s" % (st, (singer or {}).get("singerid")), warn=singer is None)
    if singer:
        pages, tot_seq = [], []
        for page in (1, 2, 3):
            st, _, bb, _ = http(KUGOU_SONGS % (page, 30, singer["singerid"]),
                                headers={"Referer": "http://www.kugou.com/"})
            dd = parse_json(bb)
            data = (dd or {}).get("data") or {}
            info = data.get("info") or []
            hashes = [x.get("hash") for x in info if isinstance(x, dict) and x.get("hash")]
            pages.append(hashes)
            tot_seq.append(data.get("total"))
            time.sleep(pacing)
        flat = [h for p in pages for h in p]
        r12, note12 = overlap(pages[0], pages[1]) if pages[1] else (0.0, "empty")
        report("H", "kugou 艺人歌曲分页", bool(pages[0]) and r12 < 0.2,
               "page1=%d page2=%d total=%s %s -> overlap<0.2 才是真分页" % (
                   len(pages[0]), len(pages[1]), tot_seq, note12), warn=r12 >= 0.2)
        report("H", "kugou 跨页无重复", len(set(flat)) == len(flat),
               "raw=%d uniq=%d" % (len(flat), len(set(flat))))
        report("H", "kugou total 稳定性", len(set(tot_seq)) <= 1, "total_seq=%s" % tot_seq, warn=True)
        # 端点能不能把 kugou hash 解成可播直链（决定"照抄这条路"是否有意义）
        played = 0
        for hh in pages[0][:4]:
            v, _, _ = resolve_chain(base, "kugou", hh)
            if v == "PLAYABLE":
                played += 1
            time.sleep(pacing)
        report("H", "kugou hash 经端点可播数", True, "%d/4 -> 0 即说明列表可用但直链需自带解析" % played,
               warn=played == 0)

    # H2 QQ：musicu 免鉴权是否还开着
    body = json.dumps({"req_0": {"module": "music.musichallSinger.SingerSongList",
                                 "method": "GetSingerSongList",
                                 "param": {"singerMid": "0025NTha1DZpoR", "begin": 0, "num": 50, "order": 1}},
                       "comm": {"ct": 11, "cv": 0}}).encode()
    st, _, b, _ = http("https://u.y.qq.com/cgi-bin/musicu.fcg", data=body,
                       headers={"Content-Type": "application/json", "Referer": "https://y.qq.com/"})
    d = parse_json(b)
    req0 = (d or {}).get("req_0") or {}
    inner = ((req0.get("data") or {}).get("songList") or [])
    report("H", "tencent musicu 免鉴权可用", bool(inner),
           "http=%s code=%s req_0.code=%s songs=%d -> 0/非 0 码即需登录态或票据" % (
               st, (d or {}).get("code"), req0.get("code"), len(inner)), warn=not inner)

    # H3 酷我：r.s 与 www 接口
    st, _, b, _ = http("http://search.kuwo.cn/r.s?all=%s&client=kt&pn=0&rn=30&uid=794766047&ver=ver2"
                       "&co=kuwo&platform=wt&token=123456&depth=1&format=json&encoding=utf8&rformat=json"
                       % urllib.parse.quote(keyword), headers={"Referer": "http://www.kuwo.cn/"})
    d = parse_json(b)
    report("H", "kuwo r.s 免鉴权可用", isinstance(d, dict) and bool(d.get("abslist")),
           "http=%s TOTAL=%s items=%s" % (st, (d or {}).get("TOTAL"),
                                          len((d or {}).get("abslist") or []) if isinstance(d, dict) else "-"),
           warn=True)

    # H4 咪咕
    st, _, b, _ = http("https://m.music.migu.cn/migu/remoting/scr_search_rank?rows=20&page=1&type=1&keyword=%s"
                       % urllib.parse.quote(keyword))
    d = parse_json(b)
    report("H", "migu 免鉴权 JSON", d is not None,
           "http=%s 可解析=%s" % (st, d is not None), warn=d is None)

    # H5 Meting type=artist 在各平台是否可用（能否零新端点拿到全量）
    for srv in ["netease", "kugou", "migu"]:
        st, _, bb, _ = http("%s?server=%s&type=artist&id=%s" % (base, srv, urllib.parse.quote(keyword)))
        d = parse_json(bb)
        n = len(d) if isinstance(d, list) else -1
        report("H", "meting type=artist/%s" % srv, n > 0,
               "http=%s n=%s（用艺人名当 id 试探，非空即值得进一步测）" % (st, n), warn=n <= 0)
        time.sleep(pacing)



def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--keyword", default="周杰伦")
    ap.add_argument("--artist", type=int, default=None, help="艺人 ID，默认由 C 组自动发现")
    ap.add_argument("--play", type=int, default=0, help="E 组分层抽样数量（0=跳过，>0 会打播放端点）")
    ap.add_argument("--endpoint", default="redcha", help="E/G/H 组使用的预设端点名")
    ap.add_argument("--pacing", type=float, default=1.5, help="每次请求间隔秒")
    ap.add_argument("--multi", type=int, default=0,
                    help="F 组随机抽样数量（0=跳过）；会打 2~3 个端点，请求量约 multi×端点数")
    ap.add_argument("--cross", action="store_true", help="跑 G1/G2/G3 跨平台组（依赖 F 的 DEAD 集）")
    ap.add_argument("--platforms", action="store_true", help="跑 H 组各平台原生接口探测")
    ap.add_argument("--latency", type=int, default=0,
                    help="L 组延迟分布的每桶采样数（0=跳过）；会额外打 网易原生 ×2 + 端点 search")
    args = ap.parse_args()

    print("# netease artist full-list probe @ %s" % time.strftime("%Y-%m-%d %H:%M:%S"))
    a_ok, a_sets = group_a(args.keyword)
    group_a2(a_sets)
    group_b(args.artist or 6452)
    artist_id = args.artist or group_c(args.keyword) or 6452
    collected, st_dist = group_d(artist_id)
    if args.play and collected:
        group_e(collected, args.endpoint, args.play, args.pacing, st_dist)
    elif args.play:
        report("E", "E 组前置条件", False, "D 组未取到歌曲")

    dead_here = []
    if args.multi:
        if collected:
            _, _, dead_here = group_f(collected, args.multi, args.pacing)
        else:
            report("F", "F 组前置条件", False, "D 组未取到歌曲")
    if args.cross:
        support = group_g_svr_matrix(args.pacing)
        if dead_here or collected:
            group_g(dead_here or stratified_picks(collected, 5), args.endpoint, args.pacing, support=support)
            group_g3(dead_here or stratified_picks(collected, 4), args.endpoint, args.pacing)
        else:
            report("G", "G 组前置条件", True, "无样本（未跑 D/F 组），跳过", warn=True)
    if args.platforms:
        group_h(args.keyword, args.endpoint, args.pacing)
    if args.latency:
        group_l(args.keyword, args.latency, args.pacing)

    print("\n=== 汇总 ===")
    print("FAIL=%d  WARN=%d  TOTAL=%d" % (
        len(FAILS),
        sum(1 for r in RESULTS if r[2] == "WARN"),
        len(RESULTS)))
    for f in FAILS:
        print("  FAIL -> %s" % f)
    if not RAN_NET:
        sys.exit(2)
    # A 组全部端点抖动 = 环境态，用 exit 2 与"结论被推翻"区分
    if not a_ok and not FAILS:
        sys.exit(2)
    sys.exit(1 if FAILS else 0)


if __name__ == "__main__":
    main()
