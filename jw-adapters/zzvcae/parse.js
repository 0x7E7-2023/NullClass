(function () {
    // 郑州汽车工程职业学院课表解析（树维 EAMS 平台）—— 第二步：教务原始数据 → 空课课表载荷。
    //
    // 移植自 shiguang_warehouse 的 ZZVCAE/zzvcae.js
    //   https://github.com/XingHeYuZhuan/shiguang_warehouse  （MIT，作者 Gr11nJ）
    //   上游快照 e62554a4034386b893bcd6813c7b2b64f8c730a3（2026-09-12）
    //
    // 这里只做纯转换（不碰页面、不发请求，CI 用 Rhino 实跑）。上游把取数与解析写在同一段
    // async 函数里，移植时全部搬到这一侧 —— 逻辑放在 extract.js 里等于没有回归。
    //
    // 上游 → 本件的移植改动，逐条：
    //   ① ES6 → ES5：async/await、模板串、箭头函数、扩展运算符、块级声明、
    //      Array.from / forEach / includes / padStart / Number.isInteger 全部换掉。
    //      上游那个 31×31 的 Set 矩阵换成定长数组，语义不变（每门课每周上哪几节）
    //   ② **周次位图口径**按本批统一约定：位图下标 i 就是第 i 周，下标 0 是占位符 ——
    //      与上游 for (week = 1; week < len; week++) 一致。下标 0 为 1 时不产出「第 0 周」，
    //      改为写一条 warnings。上游的上限是 60（远超载荷的 30），本件按批三口径 clamp 到 30
    //   ③ 位图只认「1」：上游按字符取值，位图里出现别的字符（有的部署写成 0/1 之外的记号）
    //      会静默少周；这里把非「0」「1」的字符计数并写进 warnings
    //   ④ unitCount 读不到时用缺省 14 **并进 warnings**（上游只用 console.warn，用户看不见）
    //   ⑤ index = 星期 * unitCount + 节次 的两种写法都认（带变量 / 已算好的数字）——
    //      上游的正则已经支持，这里保持，并且**绝不 eval、也不构造 Function**（手册 §5 第 6 条）
    //   ⑥ TaskActivity 的参数位：args[3] 课名、args[5] 教室、args[6] 周次位图（同族 12 件一致）。
    //      args[1]（教师）是 xxx.join(",") 这类**表达式**时必须先剥，否则会把表达式当教师名；
    //      上游是从 var teachers 块里的 actTeachers 取姓名，本件保持同一路
    //   ⑦ 教师、教室拿不到就**留空**（上游写「未知教师」「未知地点」，会被当成真姓名、真地点显示）
    //   ⑧ 课名不再截掉尾部的纯括号说明：上游 cleanCourseName 会把「（二）」这类当说明去掉，
    //      但树维的课名本身就可能带括号（「高等数学(二)」），去掉了会让两门不同的课并成同一门。
    //      只去掉 HTML 标签与空白（见 AUDIT.md「已知边界」）
    //   ⑨ 开学日不再问用户（上游 showPrompt 兜底那一段没有移植）：优先用学期起止日期，
    //      按「回退到每周起始日那一天」对齐（缺省周一），拿不到就按最近的周一推算，
    //      **每一次都写进 warnings**（手册 §4.2 / §4.3）
    //   ⑩ 学期名用教务给的学年 + 学期（「2026-2027学年第X学期」），拿不到整份学期数据时
    //      才回落到「郑州汽车工程职业学院当前学期」，并说明这是猜的
    //   ⑪ 作息时间优先用课表表头读到的（上游同款 th id="0_节次"）；**回落内置表一律进 warnings**，
    //      所有时间都过 HH:mm 与 00:00-23:59 校验（越界会让整包被拒）
    //   ⑫ 连堂合并（mergeContinuousLessons）原样移植，只把排序换成确定的比较函数：
    //      上游用 localeCompare 排中文，Rhino 与 V8 的结果未必一致，而 fixture 是逐数组比对的
    //   ⑬ warnings 上限 20 条 / 每条 200 字（载荷校验会拒），超出的在最后一条里如实说明
    var data = JSON.parse(typeof __ncInput === 'undefined' ? '{}' : __ncInput);

    var MAX_WEEK = 30;            // 载荷校验：totalWeeks ∈ 1..30，周次也按这个上限兜
    var MAX_PERIOD = 30;          // 节次号上限：超出这个数一定是字段读错了，不是真有 31 节课
    var DEFAULT_UNIT_COUNT = 14;  // 上游 zzvcae.js 的缺省值（读不到页面上那个变量时用）
    var FALLBACK_TOTAL_WEEKS = 20;
    var MAX_WARNING = 200;        // 载荷校验：单条 warnings ≤200 字，超了整包被拒
    var MAX_WARNINGS = 20;        // 载荷校验：warnings 条数上限
    var MAX_ACTIVITY_CHARS = 20000;
    var SCHOOL = '郑州汽车工程职业学院';

    // 复合键（课名 + 教师 + 教室）的分隔符取 NUL。用 String.fromCharCode 取，
    // 源码里既不出现控制字符也不出现转义序列（第一批有两个适配器把转义序列落成了真的
    // NUL 字节，文件被 grep 当二进制看）
    var SEP = String.fromCharCode(0);

    // 内置作息兜底：照上游 zzvcae.js 的 ZZQCC_TIME_SLOTS_FALLBACK 原样搬（10 节）。
    // 上游注释自己写着「暂按常见高职作息填写占位，实测后以教务系统课表页解析出的作息为准」
    // —— 所以它只是一个兜底，用了就必须在 warnings 里说明（见 AUDIT.md §4）
    var FALLBACK_PERIOD_TIMES = [
        { periodIndex: 1, start: '08:00', end: '08:45' },
        { periodIndex: 2, start: '08:55', end: '09:40' },
        { periodIndex: 3, start: '10:00', end: '10:45' },
        { periodIndex: 4, start: '10:55', end: '11:40' },
        { periodIndex: 5, start: '14:30', end: '15:15' },
        { periodIndex: 6, start: '15:25', end: '16:10' },
        { periodIndex: 7, start: '16:30', end: '17:15' },
        { periodIndex: 8, start: '17:25', end: '18:10' },
        { periodIndex: 9, start: '19:30', end: '20:15' },
        { periodIndex: 10, start: '20:25', end: '21:10' }
    ];

    var KIND_CN = { first: '一', second: '二', third: '三' };

    // ---------- 小工具 ----------
    function text(value) {
        if (value === null || value === undefined) return '';
        return String(value).replace(/\s+/g, ' ').trim();
    }

    function intOf(value) {
        var n = parseInt(value, 10);
        return isNaN(n) ? null : n;
    }

    function pad2(value) {
        return (value < 10 ? '0' : '') + value;
    }

    // 比较函数不用 localeCompare（排中文的结果跟引擎有关，而 fixture 逐数组比对）
    function cmpStr(a, b) {
        if (a === b) return 0;
        return a < b ? -1 : 1;
    }

    function cmpNum(a, b) {
        return a === b ? 0 : (a < b ? -1 : 1);
    }

    // ---------- 日期（一律用 UTC 算，避免时区把日期挪一天） ----------
    function isoOfParts(year, month, day) {
        var y = Number(year), mo = Number(month), d = Number(day);
        var date = new Date(Date.UTC(y, mo - 1, d));
        if (date.getUTCFullYear() !== y || date.getUTCMonth() + 1 !== mo || date.getUTCDate() !== d) return null;
        return String(y) + '-' + pad2(mo) + '-' + pad2(d);
    }

    // "2026-09-07" / "2026/9/7" / "2026年9月7日" / "第1周 2026-09-07" 都认
    function isoOfAny(value) {
        var source = text(value);
        if (!source) return null;
        var m = source.match(/(\d{4})[-/年](\d{1,2})[-/月](\d{1,2})日?/);
        if (!m) return null;
        return isoOfParts(m[1], m[2], m[3]);
    }

    // 这一天所在周的周一（缺省每周起始日 = 周一，手册 §4.3）
    function mondayOfIso(iso) {
        if (!iso) return null;
        var date = new Date(Date.UTC(
            parseInt(iso.substring(0, 4), 10),
            parseInt(iso.substring(5, 7), 10) - 1,
            parseInt(iso.substring(8, 10), 10)
        ));
        var offset = (date.getUTCDay() + 6) % 7;
        return isoOfDate(new Date(date.getTime() - offset * 86400000));
    }

    function isoOfDate(date) {
        return date.getUTCFullYear() + '-' + pad2(date.getUTCMonth() + 1) + '-' + pad2(date.getUTCDate());
    }

    // 最近的周一（含当天）：周次表每周从周一开始，推算开学日时用它
    function mondayNear(todayIso) {
        var iso = isoOfAny(todayIso) || isoOfDate(new Date());
        var date = new Date(Date.UTC(
            parseInt(iso.substring(0, 4), 10),
            parseInt(iso.substring(5, 7), 10) - 1,
            parseInt(iso.substring(8, 10), 10)
        ));
        var offset = (date.getUTCDay() + 6) % 7;
        return isoOfDate(new Date(date.getTime() - offset * 86400000));
    }

    // "08:00" / "08:00:00" → "08:00"；认不出（含 24:00 这种越界）返回 null。
    // 写进载荷的 periodTimes 必须是 00:00-23:59 的 HH:mm，越界会让整个载荷被拒
    function timeOf(value) {
        var m = /^([01]?\d|2[0-3]):([0-5]\d)/.exec(text(value));
        if (!m) return null;
        return (m[1].length < 2 ? '0' + m[1] : m[1]) + ':' + m[2];
    }

    function minutesOf(hhmm) {
        return parseInt(hhmm.substring(0, 2), 10) * 60 + parseInt(hhmm.substring(3, 5), 10);
    }

    // ---------- 学期 ----------
    // 树维 EAMS 的学期字段：schoolYear（"2026-2027"）+ name（"1" / "2" / "第一学期"）
    function termKind(value) {
        var source = text(value);
        if (!source) return null;
        if (source.indexOf('二') >= 0) return 'second';
        if (source.indexOf('三') >= 0) return 'third';
        if (source.indexOf('一') >= 0) return 'first';
        if (/^[123]$/.test(source)) return source === '1' ? 'first' : (source === '2' ? 'second' : 'third');
        var tail = source.match(/([123])\s*$/);
        if (tail) return tail[1] === '1' ? 'first' : (tail[1] === '2' ? 'second' : 'third');
        return null;
    }

    function yearLabelOf(value) {
        var source = text(value);
        if (!source) return '';
        var m = source.match(/(\d{4})\s*[-~/至]\s*(\d{2,4})/);
        if (m) {
            var first = m[1];
            var second = m[2].length === 2 ? String(Number(first) + 1) : m[2];
            return first + '-' + second;
        }
        if (/^\d{4}$/.test(source)) return source + '-' + String(Number(source) + 1);
        return '';
    }

    function builtName(yearLabel, kind) {
        if (!yearLabel || !kind) return null;
        return yearLabel + '学年第' + KIND_CN[kind] + '学期';
    }

    // 学期名只能猜时的判断依据：课表里的最晚周次。正常一学期不超过 20 周，
    // 排到第 21 周以后的课表多半是把两个学期排在一张表里 —— 这时候按「第 1 学期」称呼
    // 反而会写错学期名，所以叫「学年（第 N 学期）」。第 6 周都没排到说明数据太少，
    // 不足以判断，返回 false（调用方回落到带校名的兜底名并写 warnings）
    function canGuessKind(maxWeek) {
        return maxWeek >= 6;
    }

    // 两个 ISO 日期之间跨了几个自然周（首尾都算）
    function weeksBetween(startIso, endIso) {
        var start = Date.UTC(
            parseInt(startIso.substring(0, 4), 10),
            parseInt(startIso.substring(5, 7), 10) - 1,
            parseInt(startIso.substring(8, 10), 10)
        );
        var end = Date.UTC(
            parseInt(endIso.substring(0, 4), 10),
            parseInt(endIso.substring(5, 7), 10) - 1,
            parseInt(endIso.substring(8, 10), 10)
        );
        var days = Math.round((end - start) / 86400000) + 1;
        return Math.max(1, Math.round(days / 7));
    }

    // ---------- 课表表头的作息 ----------
    // 树维 EAMS 的节次表头是 th[id="0_节次"]，文本形如 "(08:00-08:45)"（上游同款正则）。
    // 只要编号不是从 1 开始连续，整份作息就不可信 —— 宁可回落内置表并说明，也不要交一张
    // 编号错位的作息表（节次与时间的对应关系错了比没有更糟）
    function parsePeriodTimes(html) {
        if (!html) return null;
        var slots = [];
        var regex = /<th[^>]*id=["']0_(\d+)["'][^>]*>([\s\S]*?)<\/th>/gi;
        var match;
        while ((match = regex.exec(String(html))) !== null) {
            var number = intOf(match[1]);
            var body = String(match[2]).replace(/<[^>]*>/g, ' ').replace(/&nbsp;|&#160;/gi, ' ');
            var time = body.match(/(\d{1,2}:\d{2})\s*[-~至]\s*(\d{1,2}:\d{2})/);
            if (number === null || !time) continue;
            var start = timeOf(time[1]);
            var end = timeOf(time[2]);
            if (!start || !end || minutesOf(start) >= minutesOf(end)) continue;
            slots.push({ periodIndex: number, start: start, end: end });
        }
        if (!slots.length) return null;
        slots.sort(function (a, b) { return cmpNum(a.periodIndex, b.periodIndex); });
        for (var i = 0; i < slots.length; i++) {
            if (slots[i].periodIndex !== i + 1) return null;
        }
        return slots;
    }

    function fallbackPeriodTimes() {
        var table = [];
        var bad = 0;
        for (var i = 0; i < FALLBACK_PERIOD_TIMES.length; i++) {
            var slot = FALLBACK_PERIOD_TIMES[i];
            var start = timeOf(slot.start);
            var end = timeOf(slot.end);
            if (!start || !end || minutesOf(start) >= minutesOf(end)) { bad++; continue; }
            table.push({ periodIndex: slot.periodIndex, start: start, end: end });
        }
        return { table: table, bad: bad };
    }

    // ---------- TaskActivity ----------
    function stripQuotes(source) {
        if (source.length < 2) return null;
        var first = source.charAt(0);
        var last = source.charAt(source.length - 1);
        if ((first === '"' || first === "'") && last === first) return source.substring(1, source.length - 1);
        return null;
    }

    // 上游的 powerSplit：按顶层逗号切参数，跳过引号与括号里的逗号
    function splitArgs(inner) {
        var args = [];
        var current = '';
        var depth = 0;
        var inQuote = false;
        var quoteChar = '';
        var previous = '';
        for (var i = 0; i < inner.length; i++) {
            var ch = inner.charAt(i);
            if ((ch === '"' || ch === "'") && previous !== '\\') {
                if (!inQuote) { inQuote = true; quoteChar = ch; }
                else if (ch === quoteChar) { inQuote = false; }
            }
            if (!inQuote) {
                if (ch === '(' || ch === '[' || ch === '{') depth++;
                if (ch === ')' || ch === ']' || ch === '}') depth--;
            }
            if (ch === ',' && depth === 0 && !inQuote) {
                args.push(current);
                current = '';
            } else {
                current += ch;
            }
            previous = ch;
        }
        args.push(current);
        return args;
    }

    // 参数的清洗：字符串字面量取内容；xxx.join(",") 这类表达式取出其中的字面量再拼
    // （同批检查表第 2 条：直接取表达式会把 join(...) 当教师名）；其余表达式一律当空，
    // **绝不 eval**（手册 §5 第 6 条）
    function cleanArg(raw) {
        var source = String(raw === null || raw === undefined ? '' : raw).trim();
        if (!source) return '';
        if (source === 'null' || /^[A-Za-z_$][A-Za-z0-9_$]*$/.test(source)) return null;
        var literal = stripQuotes(source);
        if (literal !== null) return literal;
        if (/\.\s*join\s*\(/.test(source)) {
            var found = [];
            var regex = /["']([^"']*)["']/g;
            var match;
            while ((match = regex.exec(source)) !== null) {
                if (match[1]) found.push(match[1]);
            }
            return found.length ? found.join(',') : null;
        }
        if (/^[[{]/.test(source)) return null;
        if (source.indexOf('(') >= 0) return null;
        return source;
    }

    function cleanHtml(value) {
        if (value === null || value === undefined) return '';
        return String(value)
            .replace(/<[^>]*>/g, '')
            .replace(/&nbsp;|&#160;/gi, ' ')
            .replace(/\s+/g, ' ')
            .trim();
    }

    // 块里的教师姓名：上游 parseTeacherName 同款（从 actTeachers 里取 name 字段）。
    // 树维这段内嵌脚本是 JS 对象字面量，字段名可能写成 name: 也可能写成 "name": ，
    // 两种都认（上游的正则只认前者）。
    function teachersOf(block) {
        var found = String(block).match(/actTeachers\s*=\s*\[([\s\S]*?)\]\s*;/);
        if (!found) return '';
        var names = [];
        var seen = {};
        var regex = /["']?name["']?\s*:\s*["']([^"']*)["']/g;
        var match;
        while ((match = regex.exec(found[1])) !== null) {
            var name = text(match[1]);
            if (!name || seen[name]) continue;
            seen[name] = true;
            names.push(name);
        }
        return names.join(',');
    }

    // 周次位图：下标 i 就是第 i 周，下标 0 是占位符（本批统一口径，与上游一致）。
    // 除了周次，还回一个 beyondLimit：这一块的 1 **全都**在第 MAX_WEEK 周之后
    // （数据越界，和「这门课这学期不上」要分开说，不能混成一句「读不出周次」）。
    function bitmapWeeks(bitmap, stat) {
        var source = String(bitmap === null || bitmap === undefined ? '' : bitmap);
        var weeks = [];
        var droppedHere = 0;
        for (var week = 0; week < source.length; week++) {
            if (source.charAt(week) !== '1') continue;
            // 第 0 位是占位符：不产出「第 0 周」，只计数（由调用方写进 warnings）。
            // 这一句就是本批统一口径的全部实现 —— 去掉它，第 0 位就会变成一个「第 0 周」
            if (week === 0) { stat.zeroBit++; continue; }
            if (week > MAX_WEEK) { stat.droppedWeeks++; droppedHere++; continue; }
            weeks.push(week);
        }
        return { weeks: weeks, beyondLimit: weeks.length === 0 && droppedHere > 0 };
    }

    // index = 星期 * unitCount + 节次（带变量）与 index = 62（已算好的线性下标）两种都认。
    // 两种写法都归到同一个线性下标上再拆回「星期 / 节次」—— 与同族的 HPU 同一算法。
    // 用正则解析，**不用 eval、也不构造 Function**：表达式来自网络取回的 HTML
    function sectionsOfActivity(scope, unitCount) {
        var out = [];
        var regex = new RegExp(
            '\\bindex\\s*=\\s*(?:([0-9]+)\\s*\\*\\s*(?:unitCount|' + unitCount + ')\\s*\\+\\s*([0-9]+)' +
            '|([0-9]+))\\s*;',
            'g'
        );
        var match;
        while ((match = regex.exec(scope)) !== null) {
            var linear;
            if (match[3] !== undefined && match[3] !== null) {
                linear = intOf(match[3]);
            } else {
                var day = intOf(match[1]);
                var section = intOf(match[2]);
                if (day === null || section === null) continue;
                linear = day * unitCount + section;
            }
            if (linear === null) continue;
            var rawDay = Math.floor(linear / unitCount);
            var rawSection = linear % unitCount;
            if (rawDay < 0 || rawDay > 6 || rawSection < 0 || rawSection >= unitCount) {
                stat.sectionOutOfRange++;
                continue;
            }
            out.push({ day: rawDay + 1, section: rawSection + 1 });
        }
        return out;
    }

    var stat = {
        zeroBit: 0,
        droppedWeeks: 0,
        oddChars: 0,
        sectionOutOfRange: 0,
        blocks: 0,
        activities: 0,
        courses: 0,
        noName: 0,
        noWeeks: 0,
        noIndex: 0,
        resourcesDropped: 0,
        argShort: 0,
        oversized: 0,
        teacherMissing: 0,
        placeMissing: 0
    };

    function parseLessons(html, unitCount) {
        var source = String(html);
        var lessons = [];
        var blocks = source.split(/var\s+teachers\s*=/);
        for (var b = 1; b < blocks.length; b++) {
            var block = blocks[b];
            stat.blocks++;
            var teacher = teachersOf(block);
            if (!teacher) stat.teacherMissing++;
            var activities = [];
            var regex = /new\s+TaskActivity\(([\s\S]*?)\)\s*;/g;
            var match;
            while ((match = regex.exec(block)) !== null) {
                activities.push({ args: match[1], start: match.index, end: regex.lastIndex });
            }
            for (var a = 0; a < activities.length; a++) {
                var activity = activities[a];
                stat.activities++;
                // 参数块长度设上限：正常一门课几百字符，异常大的块只会拖慢脚本
                if (activity.args.length > MAX_ACTIVITY_CHARS) { stat.oversized++; continue; }
                var args = splitArgs(activity.args);
                if (args.length < 7) { stat.argShort++; continue; }
                var name = cleanHtml(cleanArg(args[3]));
                var position = cleanHtml(cleanArg(args[5]));
                if (!position) stat.placeMissing++;
                // 周次位图里除了「1」，**别的字符一律不可信**：树维的位图可能写成 a/b 之类的记号。
                // 一旦出现未知字符，这个块的周次就不完整，宁可整块丢掉并说出来
                var bitmap = String(cleanArg(args[6]) === null ? '' : cleanArg(args[6]));
                if (!/^[01]+$/.test(bitmap)) { stat.oddChars++; continue; }
                if (!name) { stat.noName++; continue; }
                var parsedBitmap = bitmapWeeks(bitmap, stat);
                if (!parsedBitmap.weeks.length) {
                    // 「位图里一个 1 都没有」与「只有第 30 周之后才有 1」是两回事，分开计数
                    if (parsedBitmap.beyondLimit) stat.resourcesDropped++;
                    else stat.noWeeks++;
                    continue;
                }
                stat.courses++;
                var next = a + 1 < activities.length ? activities[a + 1].start : block.length;
                var scope = block.substring(activity.end, next);
                var placed = sectionsOfActivity(scope, unitCount);
                if (!placed.length) { stat.noIndex++; continue; }
                for (var p = 0; p < placed.length; p++) {
                    lessons.push({
                        name: name,
                        teacher: teacher,
                        position: position,
                        day: placed[p].day,
                        startSection: placed[p].section,
                        endSection: placed[p].section,
                        weeks: parsedBitmap.weeks.slice(0)
                    });
                }
            }
        }
        return lessons;
    }

    // ---------- 上游的 mergeContinuousLessons（原样移植，只换排序与数据结构） ----------
    // 分组键与上游逐字一致：课名|教师|教室|星期。同一组里「每个周上哪几节」记成矩阵，
    // 再把「节次段相同」的连续周并成一条 block —— 同一天两个教室的课因为教室在键里，
    // 不会互相覆盖；同一门课在同一天既上 1-2 节又上 5-6 节，会被切成两条 block。
    function mergeLessons(lessons) {
        if (!lessons || lessons.length === 0) return [];
        var groups = {};
        var order = [];
        var i, j;
        for (i = 0; i < lessons.length; i++) {
            var lesson = lessons[i];
            var key = lesson.name + SEP + lesson.teacher + SEP + lesson.position + SEP + lesson.day;
            if (!groups[key]) {
                groups[key] = {
                    name: lesson.name,
                    teacher: lesson.teacher,
                    position: lesson.position,
                    day: lesson.day,
                    weeksMatrix: []
                };
                order.push(key);
            }            var matrix = groups[key].weeksMatrix;
            for (j = 0; j < lesson.weeks.length; j++) {
                var week = lesson.weeks[j];
                if (!(week > 0) || week > MAX_WEEK) continue;
                if (!matrix[week]) matrix[week] = [];
                for (var section = lesson.startSection; section <= lesson.endSection; section++) {
                    matrix[week][section] = true;
                }
            }
        }

        var merged = [];
        for (i = 0; i < order.length; i++) {
            var group = groups[order[i]];
            var blockMap = {};
            var blockOrder = [];
            for (var weekNo = 1; weekNo < group.weeksMatrix.length; weekNo++) {
                var row = group.weeksMatrix[weekNo];
                if (!row) continue;
                var sections = [];
                for (var s = 1; s < row.length; s++) {
                    if (row[s]) sections.push(s);
                }
                if (!sections.length) continue;
                var start = sections[0];
                var previous = sections[0];
                for (var k = 1; k < sections.length; k++) {
                    var current = sections[k];
                    if (current === previous + 1) {
                        previous = current;
                    } else {
                        pushWeek(blockMap, blockOrder, start + '-' + previous, weekNo);
                        start = current;
                        previous = current;
                    }
                }
                pushWeek(blockMap, blockOrder, start + '-' + previous, weekNo);
            }
            for (var m = 0; m < blockOrder.length; m++) {
                var parts = blockOrder[m].split('-');
                merged.push({
                    name: group.name,
                    teacher: group.teacher,
                    position: group.position,
                    day: group.day,
                    startSection: intOf(parts[0]),
                    endSection: intOf(parts[1]),
                    weeks: blockMap[blockOrder[m]]
                });
            }
        }
        merged.sort(function (a, b) {
            return cmpNum(a.day, b.day) || cmpNum(a.startSection, b.startSection) ||
                cmpNum(a.endSection, b.endSection) || cmpStr(a.name, b.name) ||
                cmpStr(a.teacher, b.teacher) || cmpStr(a.position, b.position);
        });
        return merged;
    }

    function pushWeek(blockMap, blockOrder, key, week) {
        if (!blockMap[key]) { blockMap[key] = []; blockOrder.push(key); }
        blockMap[key].push(week);
    }

    // 周次数组 → 极大段（手册 §4.1）：步长 1 = 每周，步长 2 = 单/双周
    function runsOf(weeks) {
        var runs = [];
        var i = 0;
        while (i < weeks.length) {
            var step = 1;
            if (i + 1 < weeks.length && weeks[i + 1] - weeks[i] === 2) step = 2;
            var j = i;
            while (j + 1 < weeks.length && weeks[j + 1] - weeks[j] === step) j++;
            var run = { start: weeks[i], end: weeks[j], weekType: 'ALL' };
            if (step === 2 && run.start !== run.end) {
                run.weekType = run.start % 2 === 1 ? 'ODD' : 'EVEN';
            }
            runs.push(run);
            i = j + 1;
        }
        return runs;
    }

    // ---------- 学期校历（教务可选接口，拿不到就空） ----------
    function calendarOf(value) {
        var source = text(value);
        if (!source) return null;
        var start = null;
        var end = null;
        var range = source.match(
            /(\d{4})[-/年](\d{1,2})[-/月](\d{1,2})日?\s*[~—～至到\-]\s*(\d{4})[-/年](\d{1,2})[-/月](\d{1,2})日?/
        );
        if (range) {
            start = isoOfParts(range[1], range[2], range[3]);
            end = isoOfParts(range[4], range[5], range[6]);
        } else {
            var sm = source.match(/开始[^0-9]{0,8}(\d{4})[-/年](\d{1,2})[-/月](\d{1,2})/);
            var em = source.match(/结束[^0-9]{0,8}(\d{4})[-/年](\d{1,2})[-/月](\d{1,2})/);
            if (sm) start = isoOfParts(sm[1], sm[2], sm[3]);
            if (em) end = isoOfParts(em[1], em[2], em[3]);
        }
        var weeks = null;
        var wm = source.match(/(\d{1,2})\s*周/);
        if (wm) {
            var explicit = intOf(wm[1]);
            if (explicit !== null && explicit >= 1) weeks = explicit;
        }
        if (!weeks && start && end && end >= start) {
            var days = Math.round(
                (Date.UTC(intOf(end.substring(0, 4)), intOf(end.substring(5, 7)) - 1, intOf(end.substring(8, 10))) -
                    Date.UTC(intOf(start.substring(0, 4)), intOf(start.substring(5, 7)) - 1, intOf(start.substring(8, 10)))) / 86400000
            ) + 1;
            var estimated = Math.round(days / 7);
            if (estimated >= 1) weeks = estimated;
        }
        if (!start && !weeks) return null;
        return { start: start, end: end, weeks: weeks };
    }

    // ---------- 主流程 ----------
    var warnings = [];
    function warn(message) {
        var line = String(message);
        if (line.length > MAX_WARNING) line = line.substring(0, MAX_WARNING - 1) + '…';
        warnings.push(line);
    }

    var weekHtml = data.weekHtml ? String(data.weekHtml) : '';
    if (!weekHtml) {
        throw new Error(
            '没有取到课表数据（教务系统没有返回课表）：登录状态可能已失效，' +
            '请先在统一身份认证（CAS）里登录，再从服务大厅打开一次课表页后重试'
        );
    }

    // ① 节次数：能读就读，读不到用缺省值并**写进 warnings**（手册检查表第 4 条）
    var unitMatch = weekHtml.match(/\bunitCount\s*=\s*(\d+)\s*;/);
    var unitCount = unitMatch ? intOf(unitMatch[1]) : null;
    if (!unitCount || unitCount < 1 || unitCount > MAX_PERIOD) {
        unitCount = DEFAULT_UNIT_COUNT;
        warn(
            '没有从课表页读到每天的节次数（unitCount），已按缺省 ' + DEFAULT_UNIT_COUNT +
            ' 节解析：如果学校实际不是 ' + DEFAULT_UNIT_COUNT + ' 节，课表里的星期与节次会整体错位，请反馈'
        );
    }

    var lessons = parseLessons(weekHtml, unitCount);
    if (!lessons.length) {
        if (stat.activities === 0) {
            throw new Error(
                '课表里没有找到任何课程数据块（new TaskActivity）：教务系统可能改了课表格式，' +
                '请把这条消息反馈给我们'
            );
        }
        throw new Error(
            '教务系统给了 ' + stat.activities + ' 个课程数据块，但没有一个能解析出「星期 + 节次 + 周次」（' +
            '周次读不出 ' + stat.noWeeks + ' 个、节次寻址读不出 ' + stat.noIndex + ' 个、' +
            '参数不足 ' + stat.argShort + ' 个）：多半是教务系统改了课表格式，请把这条消息反馈给我们'
        );
    }
    var merged = mergeLessons(lessons);

    // ② 组装课程：课程顺序按首次出现，block 顺序确定（不依赖排序稳定性）
    var order = [];
    var byCourse = {};
    var i;
    for (i = 0; i < merged.length; i++) {
        var item = merged[i];
        var key = item.name + SEP + item.teacher;
        if (!byCourse[key]) {
            byCourse[key] = { name: item.name, teacher: item.teacher || null, note: null, blocks: [], seen: {} };
            order.push(key);
        }
    }
    var maxWeek = 0;
    var maxPeriod = 0;
    for (i = 0; i < merged.length; i++) {
        var entry = merged[i];
        var course = byCourse[entry.name + SEP + entry.teacher];
        if (!course) continue;
        var runs = runsOf(entry.weeks);
        for (var r = 0; r < runs.length; r++) {
            var run = runs[r];
            var blockKey = entry.day + '|' + entry.startSection + '|' + entry.endSection + '|' +
                run.start + '|' + run.end + '|' + run.weekType + '|' + entry.position;
            if (course.seen[blockKey]) continue;
            course.seen[blockKey] = true;
            if (run.end > maxWeek) maxWeek = run.end;
            if (entry.endSection > maxPeriod) maxPeriod = entry.endSection;
            course.blocks.push({
                dayOfWeek: entry.day,
                startPeriod: entry.startSection,
                endPeriod: entry.endSection,
                startWeek: run.start,
                endWeek: run.end,
                weekType: run.weekType,
                location: entry.position || null
            });
        }
    }
    var courses = [];
    for (i = 0; i < order.length; i++) {
        var built = byCourse[order[i]];
        built.blocks.sort(function (a, b) {
            return cmpNum(a.dayOfWeek, b.dayOfWeek) || cmpNum(a.startPeriod, b.startPeriod) ||
                cmpNum(a.endPeriod, b.endPeriod) || cmpNum(a.startWeek, b.startWeek) ||
                cmpNum(a.endWeek, b.endWeek) || cmpStr(a.weekType, b.weekType) ||
                cmpStr(a.location || '', b.location || '');
        });
        courses.push({ name: built.name, teacher: built.teacher, note: built.note, blocks: built.blocks });
    }

    // ③ 学期名 / 开学日 / 总周数
    // 学期名拿得到就按教务的学年学期（手册 §4.7：别用适配器名当学期名）；
    // 拿不到就别硬编一个：「第 N 周已排课」本身就能说明这是哪一类学期 ——
    // 课表最晚排到第 21 周以后的多半是全年课表，按「学年」而不是「第 X 学期」命名
    var semester = data.semester || null;
    var yearLabel = yearLabelOf(semester ? semester.schoolYear : null);
    var kind = termKind(semester ? semester.term : null);
    var name = builtName(yearLabel, kind);

    var semesterStart = semester ? isoOfAny(semester.startDate) : null;
    var semesterEnd = semester ? isoOfAny(semester.endDate) : null;
    var calendar = calendarOf(data.calendarHtml);
    if (!semesterStart && calendar && calendar.start) semesterStart = calendar.start;
    if (!semesterEnd && calendar && calendar.end) semesterEnd = calendar.end;
    if (!yearLabel && semesterStart) yearLabel = yearLabelOf(semesterStart.substring(0, 4));
    if (!name) name = builtName(yearLabel, kind);
    if (!name) {
        if (yearLabel && canGuessKind(maxWeek)) {
            kind = maxWeek > 20 ? 'first' : 'second';
            name = yearLabel + '学年' + (maxWeek > 20 ? '（第 1 学期）' : '（第 2 学期）');
            warn(
                '教务系统没有给出学期名（学期列表里没有这个学期的条目），学期名按「' + yearLabel +
                ' 学年 + 课表里的最晚周次（第 ' + maxWeek + ' 周）」猜成了「' + name + '」，请在学期管理里核对'
            );
        } else {
            name = SCHOOL + '当前学期';
            warn('教务系统没有给出学年学期，学期名用了「' + name + '」，请在学期管理里改成实际名称');
        }
    }

    var firstDay;
    if (semesterStart) {
        // 手册 §4.3：开学日要回退到「第 1 周的第一天」——教务给的多半是第 1 周的某一天，
        // 不是每周起始日。缺省起始日 = 周一（上游 zzvcae.js 的 firstDayOfWeek 也写死是 1）
        firstDay = mondayOfIso(semesterStart);
        warn(
            '开学日期取自教务系统的学期日期：' + semesterStart + ' 所在周的第 1 天是 ' + firstDay +
            '，已按它作为第 1 周的开始，请在学期管理里核对成学校实际开学日'
        );
    } else {
        // 开学日拿不到：上游这里是 showPrompt 问用户，移植后改成推算（手册 §4.2），
        // 推算值和真值在库里长得一模一样，所以这条说明**必须**写出来
        firstDay = mondayNear(data.today);
        warn(
            '教务系统没有给出学期日期（学期列表里没有起止日期，calendar-info 也没取到），' +
            '第 1 周已按今天（或最近）的周一 ' + firstDay + ' 推算，' +
            '如果学校不是这一天开学，整学期的课都会错位，请在学期管理里改成实际开学日'
        );
    }

    // 总周数：校历 > 学期起止日期 > 内置 20 周；最后一定夹到载荷上限内，
    // 并且**无论走哪条路都点明来源**（几个数字在库里长得一模一样，不说就没机会发现）
    var totalWeeks = 0;
    var weeksSource = '';
    if (calendar && calendar.weeks) {
        totalWeeks = calendar.weeks;
        weeksSource = '教务的学期校历（' + calendar.weeks + ' 周）';
    } else if (semesterStart && semesterEnd && semesterEnd >= semesterStart) {
        totalWeeks = weeksBetween(semesterStart, semesterEnd);
        weeksSource = '教务给出的学期起止日期（' + semesterStart + ' 到 ' + semesterEnd + '），共 ' + totalWeeks + ' 周';
    } else {
        totalWeeks = FALLBACK_TOTAL_WEEKS;
        weeksSource = '适配器内置的 ' + FALLBACK_TOTAL_WEEKS + ' 周，这是因为教务没给学期长度';
    }
    var raisedBySchedule = false;
    var clampedTotal = false;
    if (maxWeek > totalWeeks) { totalWeeks = maxWeek; raisedBySchedule = true; }
    // 载荷校验：totalWeeks ∈ 1..30，startWeek/endWeek ∈ 1..totalWeeks。
    // 周次本身已经在 bitmapWeeks 里夹到 30 以内了，这里再把总数夹一次
    // （教务的校历给 40 周这种情况：课程本身只到第 16 周，但总数也得落回上限内）
    if (totalWeeks > MAX_WEEK) { totalWeeks = MAX_WEEK; clampedTotal = true; }
    if (totalWeeks < 1) totalWeeks = FALLBACK_TOTAL_WEEKS;
    if (raisedBySchedule) {
        warn(
            '学期总周数用的是' + weeksSource + '，但课表里有第 ' + maxWeek + ' 周的课，已按 ' +
            totalWeeks + ' 周导入（否则那几周的课放不下），如与实际不符可在学期管理里改'
        );
    } else if (clampedTotal) {
        warn(
            '学期总周数用的是' + weeksSource + '，超过了空课能表示的 ' + MAX_WEEK + ' 周，' +
            '已按 ' + totalWeeks + ' 周导入，如与实际不符可在学期管理里改'
        );
    } else if (maxWeek > 0) {
        warn('学期总周数用的是' + weeksSource + '，如与实际不符可在学期管理里改');
    }

    // ④ 作息：优先用课表表头读到的（上游同款）；**回落内置表一律说明**，不静默换表
    var parsedTimes = parsePeriodTimes(data.lastTimeHtml) || parsePeriodTimes(data.pageHtml);
    var periodTimes = [];
    if (parsedTimes && parsedTimes.length) {
        periodTimes = parsedTimes;
    } else {
        var fallback = fallbackPeriodTimes();
        periodTimes = fallback.table;
        warn(
            '教务的课表页里没有读到作息时间（表头里没有 (HH:mm-HH:mm) 形式的节次时间），' +
            '已用适配器内置的 ' + periodTimes.length + ' 节作息表（第 1 节 ' +
            (periodTimes.length ? periodTimes[0].start + '-' + periodTimes[0].end : '') +
            '）：这张表是上游脚本按常见高职作息写的占位值，可能与学校实际作息不符，请在学期管理里核对'
        );
        if (fallback.bad) {
            warn('适配器内置作息表里有 ' + fallback.bad + ' 节的时间不合法（读不出时间或结束不晚于开始），已丢弃这些节次，请反馈');
        }
    }
    if (periodTimes.length && maxPeriod > periodTimes[periodTimes.length - 1].periodIndex) {
        warn(
            '课表里用到第 ' + maxPeriod + ' 节，而作息表只到第 ' + periodTimes[periodTimes.length - 1].periodIndex +
            ' 节：多出来的节次没有作息时间（课表仍按节次排，只是那几节不显示上下课时间），请核对'
        );
    }

    // ⑤ 取数与解析的对账（拿不准的一律说出来，不静默丢课、丢周次）
    if (stat.zeroBit > 0) {
        warn(
            '有 ' + stat.zeroBit + ' 门课的周次位图第 0 位是 1：树维 EAMS 的这个位是占位符，' +
            '已按「不产生第 0 周」处理，请在导入预览里核对周次'
        );
    }
    if (stat.droppedWeeks > 0) {
        warn(
            '有 ' + stat.droppedWeeks + ' 个周次超出载荷能表示的 1-' + MAX_WEEK +
            ' 周（教务给出的周次不寻常），已按上限截断，这些周的课会少'
        );
    }
    if (stat.oddChars > 0) {
        warn('有 ' + stat.oddChars + ' 个课程数据块的周次位图里有既不是 0 也不是 1 的字符，这些块的周次不可信，已整块跳过，请核对');
    }
    var droppedActivities = stat.noWeeks + stat.noIndex + stat.argShort + stat.oversized + stat.resourcesDropped + stat.noName;
    if (droppedActivities > 0) {
        var reasons = [];
        if (stat.noWeeks) reasons.push('周次位图里没有任何周（' + stat.noWeeks + ' 个）');
        if (stat.noIndex) reasons.push('星期与节次的寻址语句读不出（' + stat.noIndex + ' 个）');
        if (stat.resourcesDropped) reasons.push('周次只落在超出上限的周（' + stat.resourcesDropped + ' 个）');
        if (stat.noName) reasons.push('课程名读不出（' + stat.noName + ' 个）');
        if (stat.argShort) reasons.push('参数少于 7 个（' + stat.argShort + ' 个）');
        if (stat.oversized) reasons.push('参数块异常长（' + stat.oversized + ' 个）');
        warn(
            '课表里共有 ' + stat.activities + ' 个课程数据块，其中 ' + droppedActivities +
            ' 个没能放进课表（' + reasons.join('、') + '），这些课会少，请核对'
        );
    }
    if (stat.sectionOutOfRange > 0) {
        warn('有 ' + stat.sectionOutOfRange + ' 处星期或节次超出范围（星期 0-6、节次 0-' + (unitCount - 1) + '），已跳过，请核对');
    }
    if (stat.teacherMissing > 0) {
        warn('有 ' + stat.teacherMissing + ' 门课没有读到教师姓名，课表里这些课的教师留空：可能是教务没给，也可能是课表格式变了');
    }
    if (stat.placeMissing > 0) {
        warn('有 ' + stat.placeMissing + ' 门课没有读到上课地点（课表里这些课的地点留空），请核对是不是教务没排教室');
    }
    if (courses.length === 0) {
        throw new Error('这个学期没有解析到任何课程：可能还没排课（假期里常见），也可能教务系统改了课表格式，请核对后重试');
    }

    // ⑥ 输出（warnings 上限 20 条，超出如实说明）
    var finalWarnings = warnings;
    if (warnings.length > MAX_WARNINGS) {
        finalWarnings = warnings.slice(0, MAX_WARNINGS - 1);
        finalWarnings.push(
            '另有 ' + (warnings.length - MAX_WARNINGS + 1) + ' 条说明因为超出上限没有显示，请把这份课表反馈给我们'
        );
    }

    return JSON.stringify({
        specVersion: 1,
        kind: 'schedule',
        ocrAssisted: false,
        warnings: finalWarnings,
        terms: [
            {
                name: name,
                firstDay: firstDay,
                totalWeeks: totalWeeks,
                periodTimes: periodTimes,
                courses: courses
            }
        ]
    });
})()
