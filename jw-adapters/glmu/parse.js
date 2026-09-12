(function () {
    // 桂林医科大学教务适配器（Struts2 教务平台）—— 第二步：教务原始行 → 空课课表载荷。
    //
    // 移植自 shiguang_warehouse 的 GLMU/glmu.js
    //   https://github.com/XingHeYuZhuan/shiguang_warehouse  （MIT，作者 xhh李）
    // 上游 GLMU/glmu.js 与同平台 HBMU/hbmu.js 是逐字克隆（diff 只有文件头、URL 主机、
    // 第 1–5 节的作息时间、登录提示语四处差异），所以本适配器与 jw-adapters/hbmu 同源同结构 ——
    // 但**作息时间必须是 GLMU 自己那张表**：把 hbmu 的早晨 08:00 起照抄过来是错的，
    // 桂医第一节课是 08:30（上游 GLMU 脚本与桂医公开作息表一致）。
    // 四处的逐条核对见 AUDIT.md §4。
    // 移植改动：
    //   ① 周次解析修正：上游只把**第一个**「周」字去掉再按逗号切，遇到 "1-8周,10-16周" 会把
    //      "10-16周" 算成 NaN（整门课的周次就废了）；这里去掉所有「周」并顺带认单/双周，
    //      单/双写在「周」之后（"1-16周(双)"）或之前（"双周1-16"）都算数；
    //   ② 开学日与总周数上游没存（它只存了作息时间）：开学日优先用同平台排课日期接口的结果，
    //      拿不到就按「最近的周一」推算，并**在 warnings 里如实说明** —— 推算值和真值在库里
    //      长得一模一样，不说用户就没有任何机会发现；总周数按「不早于最晚周次、不少于 20 周」给，
    //      被课表里更晚的周次抬高时同样出声；
    //   ③ 过不了校验的行（缺课名 / 缺星期 / 缺节次 / 缺周次）逐条计数写进 warnings，不静默丢课；
    //      周次文本一个数字都解析不出来时（"第周" 这类）也逐条记原文，同样是丢数据，不许无声；
    //   ④ 作息时间用上游 GLMU 那张 12 节表（随载荷交出去）。第一节 08:30 与桂医公开作息表一致，
    //      但第 6–9 节与网上那份「临桂校区作息时间表」（第六节 14:00 起）对不上，见 AUDIT.md §5，
    //      拿不到权威口径前照上游原样给，并在 warnings 里如实说明；
    //   ⑤ 取数是否取全也写进 warnings：接口报了记录总数就对账，没报就说明「是按翻页取完的」
    //      （extract.js 在没有 total 时会一直翻到取不满一页为止）。
    //
    // 这一步是纯转换：没有任何网络与 DOM 访问，所以能（也只在）CI 里用 Rhino 真跑。
    var data = JSON.parse(__ncInput);
    if (!data || typeof data !== 'object') {
        throw new Error('适配器没有拿到教务数据：请重新登录教务系统后再点「提取课表」');
    }

    var rows = data.rows || [];
    var term = data.term || {};
    var code = textOf(term.code);
    var warnings = [];

    var MAX_WEEKS = 30;      // 载荷校验：totalWeeks ∈ 1..30
    var MIN_TOTAL_WEEKS = 20;   // 学校作息是 20 周；教务没给总周数时的兜底

    // 学校作息（上游 GLMU/glmu.js 里那张表，原样搬过来；**不是** hbmu 那张）。
    // 与 hbmu 的差别只在第 1–5 节：桂医是 08:30 起、每节 40 分钟、课间 10 分钟，第五小节 12:30 下课。
    var PERIOD_TIMES = [
        { periodIndex: 1, start: '08:30', end: '09:10' },
        { periodIndex: 2, start: '09:20', end: '10:00' },
        { periodIndex: 3, start: '10:10', end: '10:50' },
        { periodIndex: 4, start: '11:00', end: '11:40' },
        { periodIndex: 5, start: '11:50', end: '12:30' },
        { periodIndex: 6, start: '14:30', end: '15:10' },
        { periodIndex: 7, start: '15:20', end: '16:00' },
        { periodIndex: 8, start: '16:10', end: '16:50' },
        { periodIndex: 9, start: '17:00', end: '17:40' },
        { periodIndex: 10, start: '19:00', end: '19:40' },
        { periodIndex: 11, start: '19:50', end: '20:30' },
        { periodIndex: 12, start: '20:40', end: '21:20' }
    ];

    var TERM_SUFFIX = { '01': '第一学期', '02': '第二学期', '03': '短学期' };
    var DAY_NAMES = { '一': 1, '二': 2, '三': 3, '四': 4, '五': 5, '六': 6, '日': 7, '天': 7 };
    var MAX_NOTE_TOKENS = 3;    // warnings 里最多列举几段没解析出周次的原文

    function textOf(value) {
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

    // 星期：数字（"3"）、全角数字（"３"）、中文（"周三" / "星期三" / "三"）都认。
    var FULLWIDTH_DIGITS = '０１２３４５６７８９';
    var HALFWIDTH_DIGITS = '0123456789';

    function asciiDigits(value) {
        var out = '';
        for (var i = 0; i < value.length; i++) {
            var pos = FULLWIDTH_DIGITS.indexOf(value.charAt(i));
            out += pos >= 0 ? HALFWIDTH_DIGITS.charAt(pos) : value.charAt(i);
        }
        return out;
    }

    function dayOfWeekOf(value) {
        var raw = asciiDigits(textOf(value));
        if (!raw) return null;
        var direct = /^(\d{1,2})$/.exec(raw);
        if (direct) {
            var day = parseInt(direct[1], 10);
            return day >= 1 && day <= 7 ? day : null;
        }
        var chinese = /([一二三四五六日天])$/.exec(raw);
        if (chinese && (raw.indexOf('周') >= 0 || raw.indexOf('星期') >= 0 || raw.length === 1)) {
            var mapped = DAY_NAMES[chinese[1]];
            return mapped ? mapped : null;
        }
        return null;
    }

    // 同平台的 JSON 里带 HTML 实体（GDUT 那份脚本就在解码），这里用纯字符串解码 ——
    // parse.js 在 CI 里跑，没有 document 可用。
    function codePointOf(value) {
        if (!(value > 0) || value > 0x10ffff) return '';
        if (value <= 0xffff) return String.fromCharCode(value);
        var rest = value - 0x10000;
        return String.fromCharCode(0xd800 + (rest >> 10), 0xdc00 + (rest & 0x3ff));
    }

    function decodeEntities(value) {
        if (value === null || value === undefined) return '';
        var out = String(value);
        if (out.indexOf('&') < 0) return out;
        out = out
            .replace(/&nbsp;/gi, ' ')
            .replace(/&lt;/gi, '<')
            .replace(/&gt;/gi, '>')
            .replace(/&quot;/gi, '"')
            .replace(/&apos;/gi, "'");
        out = out
            .replace(/&#x([0-9a-f]{1,5});/gi, function (all, hex) { return codePointOf(parseInt(hex, 16)); })
            .replace(/&#(\d{1,6});/g, function (all, dec) { return codePointOf(parseInt(dec, 10)); });
        return out.replace(/&amp;/gi, '&');   // &amp; 最后解，免得 "&amp;lt;" 被解成 "<"
    }

    function termNameOf(source) {
        var m = /^(\d{4})(\d{2})$/.exec(source);
        if (!m) return null;
        var suffix = TERM_SUFFIX[m[2]];
        if (!suffix) return null;
        return m[1] + '-' + (parseInt(m[1], 10) + 1) + '学年' + suffix;
    }

    function parseIsoDate(value) {
        var m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(textOf(value));
        if (!m) return null;
        var date = new Date(parseInt(m[1], 10), parseInt(m[2], 10) - 1, parseInt(m[3], 10));
        return isNaN(date.getTime()) ? null : date;
    }

    function isoOf(date) {
        return date.getFullYear() + '-' + pad2(date.getMonth() + 1) + '-' + pad2(date.getDate());
    }

    function isoOfAny(value) {
        var m = /^(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})/.exec(textOf(value));
        if (!m) return null;
        var month = parseInt(m[2], 10);
        var day = parseInt(m[3], 10);
        if (month < 1 || month > 12 || day < 1 || day > 31) return null;
        return m[1] + '-' + pad2(month) + '-' + pad2(day);
    }

    // 日期接口回的是「某周的星期几 → 日期」。同平台脚本取的是 [1] 那一层（第 1 周），
    // 这里两种形状都认：先看 [1]，再看整棵树；找不到就返回 null（由调用方退回推算）。
    function firstDayFromDateInfo(dateInfo, termCode) {
        if (!dateInfo || typeof dateInfo !== 'object') return null;
        var scopes = [];
        if (dateInfo[1]) scopes.push(dateInfo[1]);
        scopes.push(dateInfo);
        for (var i = 0; i < scopes.length; i++) {
            var found = findMondayIso(scopes[i], termCode, 0);
            if (found) return found;
        }
        return null;
    }

    function findMondayIso(node, termCode, depth) {
        if (!node || typeof node !== 'object' || depth > 3) return null;
        if (typeof node.xqmc !== 'undefined' && typeof node.rq !== 'undefined') {
            var weekday = textOf(node.xqmc);
            if (weekday === '1' || weekday === '周一' || weekday === '一') {
                var iso = isoOfAny(node.rq);
                if (iso && plausibleFirstDay(iso, termCode)) return iso;
            }
            return null;
        }
        for (var key in node) {
            if (!Object.prototype.hasOwnProperty.call(node, key)) continue;
            var found = findMondayIso(node[key], termCode, depth + 1);
            if (found) return found;
        }
        return null;
    }

    // 只做粗筛：接口万一回的是别的学期，宁可按推算走（并说出来），也不要悄悄写一个错日期。
    function plausibleFirstDay(iso, termCode) {
        var m = /^(\d{4})-(\d{2})/.exec(iso);
        var c = /^(\d{4})(\d{2})$/.exec(termCode);
        if (!m || !c) return true;
        var year = parseInt(m[1], 10);
        var month = parseInt(m[2], 10);
        var startYear = parseInt(c[1], 10);
        if (c[2] === '01') return (year === startYear && month >= 7) || (year === startYear + 1 && month === 1);
        if (c[2] === '02') return year === startYear + 1 && month >= 1 && month <= 5;
        return true;
    }

    function recentMondayIso(todayIso) {
        var base = parseIsoDate(todayIso);
        if (!base) base = new Date();
        var offset = (base.getDay() + 6) % 7;
        return isoOf(new Date(base.getTime() - offset * 86400000));
    }

    // "1-16周" / "1-8周,10-16周" / "1,3,5周" / "2-16周(双)" / "双周1-16" 都认。
    // 「周」只是量词，整个删掉：早先把它换成**空格**，紧跟其后的 "(双)" 会被切成另一段，
    // 单/双标记于是整段丢掉 —— "1-16周(双)" 会当成每周都上，而且 warnings 里一个字都不提。
    //
    // 返回 { weeks, junk }：weeks 是周次数组；junk 是「有文字、却一个数字都没有」的段落原文
    //（"第周" 这类）。第二个返回值是防「认不出来的写法被静默丢掉」的 —— 认不出就得说出来。
    function weeksOfText(source) {
        var tokens = textOf(source).replace(/周/g, '').split(/[,，、;；\s]+/);
        var segments = [];
        var junk = [];
        var i;
        for (i = 0; i < tokens.length; i++) {
            if (!tokens[i]) continue;
            if (!/\d/.test(tokens[i])) junk.push(tokens[i]);
            segments.push(tokens[i]);
        }
        // 单/双标记也可能被空白切成独立的一段（"1-16周 双"）：并给最近的周次段
        //（优先往前找，前面没有就往后），免得同一个标记再丢一次。
        // 注意：这一步必须在 junk 收集**之后**做，而且只并「数字段」——
        // 括号里的纯数字序号（"(1)" 这类备注）是数字段，会被这里当成周次段，
        // 但它落在末尾、后面没有数字段可并，所以不影响任何一门课的周次（AUDIT.md §6 第 2 条）。
        for (i = 0; i < segments.length; i++) {
            if (/\d/.test(segments[i])) continue;
            if (segments[i].indexOf('单') < 0 && segments[i].indexOf('双') < 0) continue;
            var into = -1;
            var b;
            for (b = i - 1; b >= 0; b--) { if (/\d/.test(segments[b])) { into = b; break; } }
            for (b = i + 1; into < 0 && b < segments.length; b++) { if (/\d/.test(segments[b])) { into = b; break; } }
            if (into < 0) continue;
            segments[into] = segments[into] + segments[i];
            segments[i] = '';
        }
        var weeks = [];
        for (i = 0; i < segments.length; i++) {
            var segment = segments[i];
            if (!segment || !/\d/.test(segment)) continue;
            var onlyOdd = segment.indexOf('单') >= 0;
            var onlyEven = segment.indexOf('双') >= 0;
            // "单双周" 两个标记同时出现 = 每周都上；不这样兜底的话两边互相排除，整段周次会变空。
            if (onlyOdd && onlyEven) { onlyOdd = false; onlyEven = false; }
            var numbers = segment.match(/\d+/g);
            if (numbers.length >= 2) {
                var start = parseInt(numbers[0], 10);
                var end = parseInt(numbers[1], 10);
                if (isNaN(start) || isNaN(end)) continue;
                if (end < start) { var swap = start; start = end; end = swap; }
                for (var week = start; week <= end; week++) {
                    if (onlyOdd && week % 2 === 0) continue;
                    if (onlyEven && week % 2 === 1) continue;
                    weeks.push(week);
                }
            } else {
                weeks.push(parseInt(numbers[0], 10));
            }
        }
        return { weeks: uniqueSorted(weeks), junk: junk };
    }

    function uniqueSorted(weeks) {
        var seen = {};
        var out = [];
        for (var i = 0; i < weeks.length; i++) {
            var week = weeks[i];
            if (week >= 1 && !seen[week]) {
                seen[week] = true;
                out.push(week);
            }
        }
        out.sort(function (a, b) { return a - b; });
        return out;
    }

    // 连续段 → 段；步长 2 的段按首周奇偶标成单/双周（与 dlutci 的切法同口径）。
    function runsOf(weeks) {
        var runs = [];
        var i = 0;
        while (i < weeks.length) {
            var step = 1;
            if (i + 1 < weeks.length && weeks[i + 1] - weeks[i] === 2) step = 2;
            var j = i;
            while (j + 1 < weeks.length && weeks[j + 1] - weeks[j] === step) j++;
            var run = { start: weeks[i], end: weeks[j] };
            if (run.start === run.end || step === 1) {
                run.weekType = 'ALL';
            } else {
                run.weekType = run.start % 2 === 1 ? 'ODD' : 'EVEN';
            }
            runs.push(run);
            i = j + 1;
        }
        return runs;
    }

    // 节次代码：平台约定每节占两位（"0102" = 第 1、2 节，"09101112" = 9–12 节），
    // 也可能是区间写法（"03-04"）。返回首末节（与上游「取第一个和最后一个」一致）。
    // 单节的三种写法都认："9" / "第9节" / "10"（偶数长度按两位一读，所以 "10" 是第 10 节、
    // 不是第 1 节；"1112" 会被读成 11、12 两节 —— 与上游 parseSections 的逐两位一致）。
    function sectionRangeOf(source) {
        var raw = textOf(source).replace(/\s+/g, '');
        if (!raw) return null;
        var sections = [];
        var range = /^(\d{1,2})[-—~至](\d{1,2})$/.exec(raw);
        if (range) {
            var start = parseInt(range[1], 10);
            var end = parseInt(range[2], 10);
            if (isNaN(start) || isNaN(end)) return null;
            if (end < start) { var swap = start; start = end; end = swap; }
            for (var s = start; s <= end; s++) sections.push(s);
        } else if (/^\d+$/.test(raw)) {
            if (raw.length % 2 === 0) {
                for (var i = 0; i < raw.length; i += 2) sections.push(parseInt(raw.substr(i, 2), 10));
            } else {
                sections.push(parseInt(raw, 10));
            }
        } else {
            // "第3节" / "3节" / "第3" 这类带文字的写法：剥掉「第 / 节」再按上面的规则读一次。
            var stripped = raw.replace(/[第节]/g, '');
            if (!stripped || !/^\d+$/.test(stripped)) return null;
            if (stripped.length % 2 === 0) {
                for (var k2 = 0; k2 < stripped.length; k2 += 2) sections.push(parseInt(stripped.substr(k2, 2), 10));
            } else {
                sections.push(parseInt(stripped, 10));
            }
        }
        var first = null;
        var last = null;
        for (var k = 0; k < sections.length; k++) {
            var value = sections[k];
            if (isNaN(value) || value < 1) continue;
            if (first === null || value < first) first = value;
            if (last === null || value > last) last = value;
        }
        if (first === null) return null;
        return { start: first, end: last };
    }

    function previewOf(value) {
        var text = textOf(value).replace(/[\u0000-\u001f\u007f]/g, '').replace(/"/g, '');
        if (text.length > 20) text = text.substr(0, 20) + '…';
        return text;
    }

    // ---------- 学期名与开学日 ----------
    var termName = textOf(term.name) || termNameOf(code) || '教务导入';

    var firstDay = firstDayFromDateInfo(data.dateInfo, code);
    var firstDayFromSchool = !!firstDay;
    if (!firstDay) firstDay = recentMondayIso(data.today);

    if (textOf(term.source) === 'auto') {
        warnings.push('学期是按当前日期推算的（' + termName + '）。如果这不是你要导入的学期，请在学期管理里修改。');
    }
    if (!firstDayFromSchool) {
        warnings.push('开学日期无法从教务获取，已按最近的周一（' + firstDay + '）推算，请在学期管理里核对。');
    }

    // ---------- 逐行转换 ----------
    var order = [];
    var byCourse = {};
    var seenBlocks = {};
    var missing = { name: 0, day: 0, period: 0, week: 0 };
    var junkNotes = [];
    var junkRows = 0;
    var droppedWeeks = 0;
    var maxWeek = 0;

    for (var r = 0; r < rows.length; r++) {
        var row = rows[r] || {};

        var name = textOf(decodeEntities(row.kcmc));
        if (!name) { missing.name++; continue; }

        var day = dayOfWeekOf(row.xq);
        if (day === null) { missing.day++; continue; }

        var range = sectionRangeOf(row.jcdm);
        if (!range) { missing.period++; continue; }

        var parsed = weeksOfText(row.zc);
        var weeks = [];
        var overInRow = 0;
        for (var w = 0; w < parsed.weeks.length; w++) {
            if (parsed.weeks[w] > MAX_WEEKS) overInRow++;
            else weeks.push(parsed.weeks[w]);
        }
        // 认不出的周次段落：不管这门课的周次最后有没有解析出来，都要记一笔。
        // 「有周次文本、却一个数字都没解析出来」和「周次栏本来就是空的」是两回事：
        // 前者说明这所学校用了我们没认出来的写法。注意不能只在「一条周次都没解析出来」时才记 ——
        // 段落合并（单/双标记并给最近的周次段）会让标记段的原文连同它旁边的周次段一起消失，
        // 只在全丢时才记的话，「1-8周 双」这种写法会被静默吞掉。认不出就得说出来。
        if (parsed.junk.length) {
            junkRows++;
            for (var t = 0; t < parsed.junk.length && junkNotes.length < MAX_NOTE_TOKENS; t++) {
                var note = previewOf(parsed.junk[t]);
                if (note && junkNotes.indexOf(note) < 0) junkNotes.push(note);
            }
        }
        if (!weeks.length) {
            missing.week++;
            continue;
        }
        droppedWeeks += overInRow;

        var teacher = textOf(decodeEntities(row.teaxms)) || null;
        var location = textOf(decodeEntities(row.jxcdmc)) || null;
        var key = name + '\u0001' + (teacher || '');
        var course = byCourse[key];
        if (!course) {
            course = { name: name, teacher: teacher, note: null, blocks: [] };
            byCourse[key] = course;
            seenBlocks[key] = {};
            order.push(key);
        }

        var runs = runsOf(weeks);
        for (var n = 0; n < runs.length; n++) {
            var run = runs[n];
            var blockKey = day + '|' + range.start + '|' + range.end + '|' + run.start + '|' + run.end + '|' + run.weekType + '|' + (location || '');
            if (seenBlocks[key][blockKey]) continue;   // 翻页重叠导致的重复行：同一条安排只留一条
            seenBlocks[key][blockKey] = true;
            if (run.end > maxWeek) maxWeek = run.end;
            course.blocks.push({
                dayOfWeek: day,
                startPeriod: range.start,
                endPeriod: range.end,
                startWeek: run.start,
                endWeek: run.end,
                weekType: run.weekType,
                location: location
            });
        }
    }

    if (order.length === 0) {
        throw new Error('这学期的课表里没有解析出任何课程：可能是课表还没发布，或教务系统改了数据格式');
    }

    // 教务接口不给学期总周数（上游脚本也没存）：不能早于课表里最晚的周次，也不能少于学校作息的 20 周。
    // 被课表里更晚的周次抬高时必须出声 —— 抬高的是整学期的长度，用户不核对就发现不了。
    var totalWeeks = maxWeek > MIN_TOTAL_WEEKS ? maxWeek : MIN_TOTAL_WEEKS;
    if (totalWeeks > MAX_WEEKS) totalWeeks = MAX_WEEKS;
    if (maxWeek > MIN_TOTAL_WEEKS) {
        warnings.push('教务没有给出学期总周数，课表里最晚排到第 ' + maxWeek + ' 周，已把学期总周数按 ' +
            totalWeeks + ' 周计，如与实际不符可在学期管理里改。');
    } else {
        warnings.push('教务没有给出学期总周数，已取 ' + totalWeeks + ' 周（课表里最晚到第 ' + maxWeek + ' 周，学校作息按 ' +
            MIN_TOTAL_WEEKS + ' 周），如与实际不符可在学期管理里改。');
    }

    // 作息时间表：上游 GLMU 脚本自带的那张。第 1–5 节与桂医公开作息表一致（08:30 起），
    // 第 6–9 节与网上那份「临桂校区作息时间表」对不上（那份是第六节 14:00 起）。
    // 拿不到权威口径之前照上游原样给，但**必须说出来** —— 作息表决定每节课显示的时间。
    warnings.push('上下课时间取自适配器内置的桂医作息表（第一节 08:30），下午与晚上的时间未能与学校公开作息表核对一致，请在「学期管理」里核对一遍。');

    var skipped = missing.name + missing.day + missing.period + missing.week;
    if (skipped > 0) {
        var reasons = [];
        if (missing.name) reasons.push('缺课程名 ' + missing.name + ' 条');
        if (missing.day) reasons.push('缺星期 ' + missing.day + ' 条');
        if (missing.period) reasons.push('缺节次 ' + missing.period + ' 条');
        if (missing.week) reasons.push('缺周次 ' + missing.week + ' 条');
        warnings.push('有 ' + skipped + ' 条课程记录信息不完整，已跳过（' + reasons.join('、') + '）。');
    }
    if (junkRows > 0) {
        warnings.push('有 ' + junkRows + ' 条课程记录的周次栏里有认不出的写法（原文如「' + junkNotes.join('」「') +
            '」），这些段落已被忽略；请核对课表，并把这所学校的周次写法反馈给适配器作者。');
    }
    if (droppedWeeks > 0) {
        warnings.push('有 ' + droppedWeeks + ' 个周次超出 ' + MAX_WEEKS + ' 周上限，已忽略。');
    }

    // 取数是不是取全了：接口回了 total 就按 total 对账；没回 total 时 extract.js 是「每页取满
    // 就继续翻、取不满为止」取完的（见 extract.js 的 fetchRows），这种情况也要如实说出来 ——
    // 没有总数就没有任何东西能证明课表是完整的，不说用户没有机会发现。
    var total = intOf(data.total);
    if (total !== null) {
        if (total > rows.length) {
            warnings.push('教务系统返回 ' + total + ' 条记录，只取到 ' + rows.length + ' 条，课表可能不完整。');
        }
    } else if (data.truncated === true) {
        warnings.push('教务系统没有返回记录总数，翻页已经到了页数上限、后面可能还有没取到的记录（已取 ' + rows.length + ' 条），课表可能不完整。');
    } else {
        warnings.push('教务系统没有返回记录总数，课表是按翻页取完的（共 ' + rows.length + ' 条），请核对课表是否完整。');
    }

    var courses = [];
    for (var c = 0; c < order.length; c++) courses.push(byCourse[order[c]]);

    return JSON.stringify({
        specVersion: 1,
        kind: 'schedule',
        ocrAssisted: false,
        warnings: warnings,
        terms: [
            {
                name: termName,
                firstDay: firstDay,
                totalWeeks: totalWeeks,
                periodTimes: PERIOD_TIMES,
                courses: courses
            }
        ]
    });
})()
