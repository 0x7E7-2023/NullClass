(function () {
    // 河南财经政法大学课表解析（正方新版 jwglxt 平台）
    // 移植自 shiguang_warehouse 的 HUEL/huel_01.js
    //   https://github.com/XingHeYuZhuan/shiguang_warehouse  （MIT，上游 maintainer Mercury）
    //   上游快照 e62554a4034386b893bcd6813c7b2b64f8c730a3（2026-09-12）
    // 这里只做纯转换（不碰页面、不发请求，CI 用 Rhino 实跑）：
    //   ① 正方一行一条排课：星期 xqj、节次 jcs（"1-2" / "01-02" / "0102" / "第9,10节"）、
    //      周次 zcd（"1-16周" / "1-16周(单)" / "1-18周全周" / "1-4,6-8周"）
    //   ② 周次文本 → 周次集合 → 极大段（移植手册 §4.1），一段 = 一条 block
    //   ③ 同一门课（课名 + 教师）的多行合并成一门课的多个 block，完全重复的行去掉
    //   ④ 脏数据一律出声：缺课名 / 星期的行、节次读不出来的行、节次超出第 1-16 节的行、
    //      没有周次的行分别跳过并计数，周次里超出 1-30 的部分丢掉并计数 —— 每一类都进 warnings
    //      （手册：不许静默丢数据）
    //   ⑤ 开学日期：正方接口不给，按学期推算（上游脚本给第一 / 二学期的默认开学日是
    //      <学年>-09-01 与 <学年+1>-03-01，这里就按这两个锚点找所在周的周一），并在
    //      warnings 里如实说明；总周数取课表里出现的最晚周次（含单双周区间声明的上界），
    //      教务另外报了总周数而更小时按后者抬高并说明
    //   ⑥ 作息表用上游脚本里的河南财经政法大学节次表（13 节，第 11-13 节在晚上）
    var data = JSON.parse(typeof __ncInput === 'undefined' ? '{}' : __ncInput);
    var term = data.term || {};
    var meta = data.meta || {};
    var raw = data.raw || {};
    var rows = [];
    if (Array.isArray(raw.kbList)) rows = raw.kbList;
    else if (Array.isArray(raw.rows)) rows = raw.rows;

    var MAX_WEEK = 30;
    // 节次上限：内置作息表到第 13 节，允许再多认 3 节（有的部署排到第 14 节），
    // 再往上就是脏数据 —— 超出上限的行整行跳过并计数，不静默丢
    var MAX_PERIOD = 16;

    // 学校作息时间（河南财经政法大学，取自上游脚本里的预设节次表）
    var PERIOD_TIMES = [
        { periodIndex: 1, start: '08:00', end: '08:45' },
        { periodIndex: 2, start: '08:55', end: '09:40' },
        { periodIndex: 3, start: '09:55', end: '10:40' },
        { periodIndex: 4, start: '10:50', end: '11:35' },
        { periodIndex: 5, start: '11:45', end: '12:30' },
        { periodIndex: 6, start: '13:30', end: '14:15' },
        { periodIndex: 7, start: '14:25', end: '15:10' },
        { periodIndex: 8, start: '15:25', end: '16:10' },
        { periodIndex: 9, start: '16:20', end: '17:05' },
        { periodIndex: 10, start: '17:15', end: '18:00' },
        { periodIndex: 11, start: '19:00', end: '19:45' },
        { periodIndex: 12, start: '19:50', end: '20:35' },
        { periodIndex: 13, start: '20:40', end: '21:25' }
    ];
    var TABLE_PERIODS = PERIOD_TIMES.length;

    var KIND_CN = { first: '一', second: '二', third: '三' };
    // 开学日的推算锚点：上游脚本给第一学期的默认开学日是 <学年>-09-01、第二学期是
    // <学年+1>-03-01，这里按「锚点所在周的周一」取（手册 §4.3：别把锚点日直接当 firstDay）
    var KIND_ANCHOR = {
        first: { month: 9, day: 1, rule: '第一学期 = 学年 9 月 1 日所在周的周一' },
        second: { month: 3, day: 1, rule: '第二学期 = 次年 3 月 1 日所在周的周一' },
        third: { month: 7, day: 1, rule: '夏季学期 = 7 月 1 日所在周的周一' }
    };

    function text(value) {
        if (value === null || value === undefined) return '';
        return String(value).replace(/\s+/g, ' ').trim();
    }

    function intOf(value) {
        var n = parseInt(value, 10);
        return isNaN(n) ? null : n;
    }

    function pad2(n) {
        return (n < 10 ? '0' : '') + n;
    }

    function isoOf(date) {
        return date.getUTCFullYear() + '-' + pad2(date.getUTCMonth() + 1) + '-' + pad2(date.getUTCDate());
    }

    // 该日期所在周的周一（用 UTC 算，避免时区把日期挪一天）
    function mondayOnOrBefore(year, month, day) {
        var date = new Date(Date.UTC(year, month - 1, day));
        var offset = (date.getUTCDay() + 6) % 7;
        return new Date(date.getTime() - offset * 86400000);
    }

    // 学期序号：优先看教务给的下拉框文本（「一 / 二 / 三」「1 / 2 / 3」「第一学期」都见过），
    // 文本认不出来再看正方代号（正方 jwglxt 用 3 / 12 / 16 表示第一、二、三学期；
    // 上游 HUEL 脚本也是按 3 = 第一学期写默认开学日的）
    function termKind() {
        var label = text(term.xqmText);
        if (label.indexOf('第一') >= 0 || label === '一' || label === '1') return 'first';
        if (label.indexOf('第二') >= 0 || label === '二' || label === '2') return 'second';
        if (label.indexOf('第三') >= 0 || label === '三' || label === '3') return 'third';
        var code = text(term.xqm);
        if (code === '12' || code === '2') return 'second';
        if (code === '16') return 'third';
        return 'first';
    }

    function yearLabel() {
        var label = text(term.xnmText);
        if (/^[0-9]{4}-[0-9]{2,4}$/.test(label)) return label;
        var year = /^[0-9]{4}$/.test(text(term.xnm)) ? parseInt(term.xnm, 10) : 0;
        if (year) return year + '-' + (year + 1);
        return label || text(term.xnm);
    }

    function termName() {
        var explicit = text(term.name);
        if (explicit) return explicit;
        var whole = text(term.xqmText);
        // 有的学校把整个「2026-2027学年第一学期」放进学期下拉框
        if (whole && whole.indexOf('学年') >= 0 && whole.indexOf('学期') >= 0) return whole;
        return yearLabel() + '学年第' + KIND_CN[termKind()] + '学期';
    }

    function estimateStart() {
        var kind = termKind();
        var anchor = KIND_ANCHOR[kind];
        var year = /^[0-9]{4}$/.test(text(term.xnm)) ? parseInt(term.xnm, 10) : 0;
        if (!year) {
            // 学年读不出来（课表页被改过）时退回「今天的周一」。这条分支依赖当天日期，
            // 不要写进 fixture 用例 —— 用例会随日期失效
            var today = new Date();
            return {
                iso: isoOf(mondayOnOrBefore(today.getFullYear(), today.getMonth() + 1, today.getDate())),
                rule: '今天的周一'
            };
        }
        if (kind === 'second' || kind === 'third') year = year + 1;
        return { iso: isoOf(mondayOnOrBefore(year, anchor.month, anchor.day)), rule: anchor.rule };
    }

    // 周次文本 → 周次集合（去重、升序）。正方：整个学期一张表，周次都写在 zcd 一个字段里，
    // 从不与课名 / 教师 / 教室混在一段文字里（这一点与需要切单元格的平台不同）。
    //
    // 两个必须小心的写法：
    //   ① 「单 / 双」可能写在「周」字后面（"1-16周(双)"）：先认标记，再清理文本，别把它洗掉；
    //      同一段里「单」和「双」都出现（如 "1-16周(单双)"）时按每周都上处理并计数报警
    //   ② 括号里的纯数字是序号（"3-4周(1)" 的 (1)）：括号外有周次数字时就完全不看括号里，
    //      免得把 (1) 当成第 1 周、或者把真实周次吃掉
    var droppedWeekSpans = 0;
    var parityBoth = 0;
    var declaredMaxWeek = 0;

    function weeksOf(source) {
        var body = text(source).replace(/\s+/g, '');
        var seen = {};
        var weeks = [];
        var segments = body.split(/[,，、;；]/);
        for (var i = 0; i < segments.length; i++) {
            var segment = segments[i];
            if (!segment) continue;
            var hasOdd = segment.indexOf('单') >= 0;
            var hasEven = segment.indexOf('双') >= 0;
            if (hasOdd && hasEven) parityBoth++;
            var onlyOdd = hasOdd && !hasEven;
            var onlyEven = hasEven && !hasOdd;
            var outside = segment.replace(/[（(][^（()）]*[)）]/g, '');
            var picked = /[0-9]/.test(outside) ? outside : segment;
            var cleaned = picked.replace(/周/g, '').replace(/第/g, '');
            var match = /([0-9]+)(?:-([0-9]+))?/.exec(cleaned);
            if (!match) continue;
            var start = parseInt(match[1], 10);
            var end = match[2] ? parseInt(match[2], 10) : start;
            if (isNaN(start) || isNaN(end) || end < start) continue;
            if (start < 1 || end > MAX_WEEK) droppedWeekSpans++;
            if (end > declaredMaxWeek && end <= MAX_WEEK) declaredMaxWeek = end;
            var from = start < 1 ? 1 : start;
            var to = end > MAX_WEEK ? MAX_WEEK : end;
            for (var week = from; week <= to; week++) {
                if (onlyOdd && week % 2 === 0) continue;
                if (onlyEven && week % 2 === 1) continue;
                if (!seen[week]) {
                    seen[week] = true;
                    weeks.push(week);
                }
            }
        }
        weeks.sort(function (a, b) { return a - b; });
        return weeks;
    }

    // 周次集合 → 极大段：步长 1 视作每周，步长 2 视作单周 / 双周（手册 §4.1）
    function runsOf(weeks) {
        var runs = [];
        var i = 0;
        while (i < weeks.length) {
            var step = 1;
            if (i + 1 < weeks.length && weeks[i + 1] - weeks[i] === 2) step = 2;
            var j = i;
            while (j + 1 < weeks.length && weeks[j + 1] - weeks[j] === step) j++;
            var run = { startWeek: weeks[i], endWeek: weeks[j], weekType: 'ALL' };
            if (step === 2 && run.startWeek !== run.endWeek) {
                run.weekType = run.startWeek % 2 === 1 ? 'ODD' : 'EVEN';
            }
            runs.push(run);
            i = j + 1;
        }
        return runs;
    }

    // 节次文本 → [起始节, 结束节]。认这些写法：
    //   "1-2" / "1-2节" / "01-02节" / "第9,10节" / "9,10" / "1-4"（连堂区间）
    //   / "0102"（两位一节连写）/ "1-2,3-4"（一段连堂拆成两个 token）
    // 读不出来时返回 null（一个数字都没有），数字有但超出第 1-MAX_PERIOD 节时返回 'over' ——
    // 两种情况由调用方分别计数报警，都不静默丢数据
    // 一行里出现不连续的两段（"1-2,5-6"）时只取前一段并计数报警 —— 否则会拼出一条
    // 横跨 1-6 节的假连堂
    var splitSections = 0;

    // 一个 token → [起, 止]："3-4" 是区间，"0102" 是两位一节连写，"9" 是单节
    function spanOf(token) {
        if (!token) return null;
        var range = /^([0-9]+)-([0-9]+)$/.exec(token);
        if (range) {
            var a = parseInt(range[1], 10);
            var b = parseInt(range[2], 10);
            if (isNaN(a) || isNaN(b)) return null;
            return a <= b ? [a, b] : [b, a];
        }
        var numbers = [];
        var i;
        if (/^[0-9]+$/.test(token) && token.length >= 4 && token.length % 2 === 0) {
            for (i = 0; i < token.length; i += 2) numbers.push(parseInt(token.substring(i, i + 2), 10));
        } else {
            var found = token.match(/[0-9]+/g) || [];
            for (i = 0; i < found.length; i++) numbers.push(parseInt(found[i], 10));
        }
        if (!numbers.length) return null;
        var lo = numbers[0];
        var hi = numbers[0];
        for (i = 1; i < numbers.length; i++) {
            if (numbers[i] < lo) lo = numbers[i];
            if (numbers[i] > hi) hi = numbers[i];
        }
        return [lo, hi];
    }

    function periodsOf(row) {
        var source = text(row.jcs) || text(row.jc) || text(row.jcor);
        if (!source) return null;
        var body = source.replace(/\s+/g, '').replace(/节/g, '').replace(/第/g, '');
        // 全角数字与全角逗号按半角处理（有的部署会带）
        body = body.replace(/[０-９]/g, function (ch) {
            return String.fromCharCode(ch.charCodeAt(0) - 65248);
        });
        body = body.replace(/[，、]/g, ',');
        var tokens = body.split(/[,;]/);
        var start = null;
        var end = null;
        var extra = false;
        var i;
        for (i = 0; i < tokens.length; i++) {
            var span = spanOf(tokens[i]);
            if (!span) continue;
            if (start === null) {
                start = span[0];
                end = span[1];
                continue;
            }
            // 与上一段连得上（"9,10" 或 "1-2,3-4"）就并进去，接不上说明还有第二段
            if (span[0] <= end + 1) {
                if (span[1] > end) end = span[1];
                continue;
            }
            extra = true;
            break;
        }
        if (start === null) return null;
        if (extra) splitSections++;
        if (start < 1 || end > MAX_PERIOD) return 'over';
        return [start, end];
    }

    // 作息表补齐：课表里出现的节次必须每一节都有上下课时间（连堂 1-4 节就要有 1、2、3、4），
    // 内置表只有 13 节，超出的按上一节的时长顺延估算，并把估算这件事写进 warnings
    function minutesOf(value) {
        var match = /^([0-9]{1,2}):([0-9]{2})$/.exec(text(value));
        if (!match) return 0;
        return parseInt(match[1], 10) * 60 + parseInt(match[2], 10);
    }

    function hhmm(minutes) {
        var m = minutes;
        if (m < 0) m = 0;
        if (m > 1439) m = 1439;
        return pad2(Math.floor(m / 60)) + ':' + pad2(m % 60);
    }

    function fillPeriods(upTo) {
        var added = 0;
        while (PERIOD_TIMES.length < upTo && PERIOD_TIMES.length < MAX_PERIOD) {
            var last = PERIOD_TIMES[PERIOD_TIMES.length - 1];
            var start = minutesOf(last.end) + 10;
            var end = start + 45;
            if (end > 1439) end = 1439;
            if (start >= end) break;
            PERIOD_TIMES.push({ periodIndex: last.periodIndex + 1, start: hhmm(start), end: hhmm(end) });
            added++;
        }
        return added;
    }

    var brokenRows = 0;
    var noSectionRows = 0;
    var overSectionRows = 0;
    var noWeekRows = 0;

    var order = [];
    var byName = {};
    var maxUsedPeriod = TABLE_PERIODS;
    var maxUsedWeek = 0;

    for (var r = 0; r < rows.length; r++) {
        var row = rows[r] || {};
        var name = text(row.kcmc);
        var day = intOf(row.xqj);
        if (!name || !(day >= 1 && day <= 7)) {
            brokenRows++;
            continue;
        }
        var periods = periodsOf(row);
        if (periods === 'over') {
            overSectionRows++;
            continue;
        }
        if (!periods) {
            noSectionRows++;
            continue;
        }
        var weeks = weeksOf(row.zcd);
        if (!weeks.length) {
            noWeekRows++;
            continue;
        }

        // 教师、教室没有就让它是空的（上游写的「未知」「未排地点」在课表里会当成真名显示）
        var teacher = text(row.xm) || null;
        var location = text(row.cdmc) || text(row.cdbh) || null;

        // 课名带前缀再当键：课名万一是「constructor」「toString」这类 Object 原型上的名字，
        // 直接当键会撞上原型链上的成员
        var listKey = '#' + name;
        var list = byName[listKey];
        if (!list) {
            list = [];
            byName[listKey] = list;
            order.push(listKey);
        }
        var course = null;
        for (var c = 0; c < list.length; c++) {
            if (list[c].teacher === teacher) {
                course = list[c];
                break;
            }
        }
        if (!course) {
            course = { name: name, teacher: teacher, note: null, blocks: [], seen: {} };
            list.push(course);
        }

        if (periods[1] > maxUsedPeriod) maxUsedPeriod = periods[1];
        var runs = runsOf(weeks);
        for (var k = 0; k < runs.length; k++) {
            var run = runs[k];
            var blockKey = day + '|' + periods[0] + '|' + periods[1] + '|' + run.startWeek + '|' +
                run.endWeek + '|' + run.weekType + '|' + (location || '');
            if (course.seen[blockKey]) continue;
            course.seen[blockKey] = true;
            if (run.endWeek > maxUsedWeek) maxUsedWeek = run.endWeek;
            course.blocks.push({
                dayOfWeek: day,
                startPeriod: periods[0],
                endPeriod: periods[1],
                startWeek: run.startWeek,
                endWeek: run.endWeek,
                weekType: run.weekType,
                location: location
            });
        }
    }

    if (!order.length) {
        throw new Error(
            '这个学期没有解析到任何课程：可能还没排课，也可能登录状态已失效。' +
            '请重新登录、打开「信息查询 - 学生课表查询」确认能看到课表后再试'
        );
    }

    var courses = [];
    for (var o = 0; o < order.length; o++) {
        var group = byName[order[o]];
        for (var g = 0; g < group.length; g++) {
            courses.push({
                name: group[g].name,
                teacher: group[g].teacher,
                note: group[g].note,
                blocks: group[g].blocks
            });
        }
    }

    // 总周数：正方不给校历周数。课表里声明的上界（含被单双过滤掉的那一半，例如「1-16周(单)」
    // 声明的是 16 周而不是 15）与实际排到的周次取大；教务若另外报了总周数而更小，按前者抬高
    // 并写进 warnings（手册：推算值必须说出来）
    var declaredWeeks = intOf(term.totalWeeks);
    if (!(declaredWeeks >= 1)) declaredWeeks = intOf(term.semesterTotalWeeks);
    if (!(declaredWeeks >= 1)) declaredWeeks = intOf(meta.totalWeeks);

    var baseWeeks = maxUsedWeek;
    if (declaredMaxWeek > baseWeeks) baseWeeks = declaredMaxWeek;
    if (!(baseWeeks >= 1)) baseWeeks = 1;
    if (baseWeeks > MAX_WEEK) baseWeeks = MAX_WEEK;

    var totalWeeks = baseWeeks;
    var raisedFrom = 0;
    if (declaredWeeks >= 1 && declaredWeeks < baseWeeks) {
        raisedFrom = declaredWeeks;
    } else if (declaredWeeks >= 1) {
        totalWeeks = declaredWeeks;
    }
    if (totalWeeks > MAX_WEEK) totalWeeks = MAX_WEEK;

    var filled = fillPeriods(maxUsedPeriod);

    var name0 = termName();
    var start = estimateStart();
    var warnings = [];

    warnings.push(
        '正方课表接口不给开学日期，第 1 周按「' + start.rule + '」推算为 ' + start.iso +
        '，请在学期管理里核对成学校实际开学日'
    );
    warnings.push(
        '只导入了教务系统当前选中的学期（' + name0 +
        '）；要导入别的学期，请在教务页面里切到那个学期再点「提取课表」'
    );
    if (raisedFrom >= 1) {
        warnings.push(
            '教务给出的学期总周数是 ' + raisedFrom + ' 周，但课表里有排到第 ' + totalWeeks +
            ' 周的课，已按 ' + totalWeeks + ' 周导入（总周数不能小于课表里的最晚周次）'
        );
    } else if (declaredWeeks >= 1) {
        warnings.push(
            '学期总周数用的是教务报的 ' + totalWeeks + ' 周，如与实际不符可在学期管理里改'
        );
    } else {
        warnings.push(
            '学期总周数取的是课表里出现的最晚周次（' + totalWeeks +
            ' 周），不是校历周数，如与实际不符可在学期管理里改'
        );
    }
    if (meta.declaredTotal !== null && meta.declaredTotal !== undefined) {
        var declaredTotal = intOf(meta.declaredTotal);
        if (declaredTotal !== null && declaredTotal > rows.length) {
            warnings.push(
                '课表接口报的记录总数是 ' + declaredTotal + ' 条，实际只取到 ' + rows.length +
                ' 条（补取没成功），可能漏课：请重新登录后再试，或反馈给适配器维护者'
            );
        }
    }
    if (brokenRows > 0) {
        warnings.push(
            '有 ' + brokenRows + ' 行课表数据缺课程名或星期，已跳过：' +
            '教务数据不完整时会出现，如发现少课请反馈'
        );
    }
    if (noSectionRows > 0) {
        warnings.push(
            '有 ' + noSectionRows + ' 行课表数据的节次读不出来，已跳过：' +
            '教务改版或数据不完整时会出现，如发现少课请反馈'
        );
    }
    if (overSectionRows > 0) {
        warnings.push(
            '有 ' + overSectionRows + ' 行课表数据的节次超出第 1-' + MAX_PERIOD + ' 节的范围，已跳过：' +
            '如发现少课请反馈'
        );
    }
    if (noWeekRows > 0) {
        warnings.push('有 ' + noWeekRows + ' 行课表数据没有任何周次，已跳过：如发现少课请反馈');
    }
    if (splitSections > 0) {
        warnings.push(
            '有 ' + splitSections + ' 行的节次写成不连续的两段（如「1-2,5-6」），只导入了前一段：' +
            '如发现少课请反馈'
        );
    }
    if (filled > 0) {
        warnings.push(
            '有课排在内置作息表（第 1-' + TABLE_PERIODS + ' 节）之后，第 ' + (TABLE_PERIODS + 1) +
            ' 节起的时间是按上一节顺延估算的，请在学期管理里核对'
        );
    }
    if (droppedWeekSpans > 0) {
        warnings.push(
            '有 ' + droppedWeekSpans + ' 处周次的写法超出 1-' + MAX_WEEK + ' 周，超出部分已丢弃' +
            '（教务给出的周次不正常）'
        );
    }
    if (parityBoth > 0) {
        warnings.push(
            '有 ' + parityBoth + ' 处周次同时写了「单」和「双」' +
            '（如「1-16周(单双)」），已按每周都上处理，请核对'
        );
    }

    return JSON.stringify({
        specVersion: 1,
        kind: 'schedule',
        ocrAssisted: false,
        warnings: warnings,
        terms: [
            {
                name: name0,
                firstDay: start.iso,
                totalWeeks: totalWeeks,
                periodTimes: PERIOD_TIMES,
                courses: courses
            }
        ]
    });
})()
