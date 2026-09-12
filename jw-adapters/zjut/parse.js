(function () {
    // 浙江工业大学课表解析（正方新版 jwglxt 平台）
    // 移植自 shiguang_warehouse 的 ZJUT/zjut_01.js
    //   https://github.com/XingHeYuZhuan/shiguang_warehouse  （MIT，作者 glxgo）
    //   上游快照 e62554a4034386b893bcd6813c7b2b64f8c730a3（2026-09-12）
    // 这里只做纯转换（不碰页面、不发请求，CI 用 Rhino 实跑）：
    //   ① 正方一行一条排课：星期 xqj、节次 jcs（"1-2"）、周次 zcd（"1-16周" / "1-16周(单)" / "1-4,6-8周"）
    //   ② 周次文本 → 周次集合 → 极大段（移植手册 §4.1），一段 = 一条 block
    //   ③ 同一门课（课名 + 教师）的多行合并成一门课的多个 block，完全重复的行去掉
    //   ④ 解析不了的行、超出 1-30 的周次：跳过并写进 warnings（手册：不许静默丢数据）
    //   ⑤ 开学日期：正方接口不给，按学年学期推算并在 warnings 里如实说明（手册 §4.2）
    var data = JSON.parse(typeof __ncInput === 'undefined' ? '{}' : __ncInput);
    var term = data.term || {};
    var raw = data.raw || {};
    var rows = raw.kbList || [];

    var MAX_WEEK = 30;

    // 学校作息时间（浙江工业大学，取自上游脚本里的预设节次表）
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
        { periodIndex: 10, start: '18:30', end: '21:30' }
    ];

    var KIND_CN = { first: '一', second: '二', third: '三' };
    // 开学日的推算锚点：中国高校第一学期多在 9 月初、第二学期多在 2 月下旬、夏季学期 7 月初
    var KIND_ANCHOR = {
        first: { month: 9, day: 1, rule: '第一学期 = 9 月 1 日所在周的周一' },
        second: { month: 2, day: 20, rule: '第二学期 = 2 月 20 日所在周的周一' },
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

    // 学期序号：优先看教务给的下拉框文本（「一/二/三」），文本认不出来再看正方代号
    // （正方 jwglxt 用 3 / 12 / 16 表示第一、二、三学期）
    function termKind() {
        var label = text(term.xqmText);
        if (label.indexOf('二') >= 0) return 'second';
        if (label.indexOf('三') >= 0) return 'third';
        if (label.indexOf('一') >= 0) return 'first';
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

    // 周次文本 → 周次集合（去重、升序）。正方的写法："1-16周" / "1-16周(单)" / "2-6周(双),10周" / "1-4,6-8周"
    var droppedWeeks = 0;

    function weeksOf(source) {
        var text0 = text(source).replace(/\s+/g, '');
        var seen = {};
        var weeks = [];
        var segments = text0.split(/[,，;；]/);
        for (var i = 0; i < segments.length; i++) {
            var segment = segments[i];
            if (!segment) continue;
            var onlyOdd = segment.indexOf('单') >= 0;
            var onlyEven = segment.indexOf('双') >= 0;
            var cleaned = segment.replace(/周|\(|\)|（|）|单|双/g, '');
            var match = /([0-9]+)(?:-([0-9]+))?/.exec(cleaned);
            if (!match) continue;
            var start = parseInt(match[1], 10);
            var end = match[2] ? parseInt(match[2], 10) : start;
            if (isNaN(start) || isNaN(end) || end < start) continue;
            for (var week = start; week <= end; week++) {
                if (onlyOdd && week % 2 === 0) continue;
                if (onlyEven && week % 2 === 1) continue;
                if (week < 1 || week > MAX_WEEK) {
                    droppedWeeks++;
                    continue;
                }
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
            var run = { start: weeks[i], end: weeks[j], weekType: 'ALL' };
            if (step === 2 && run.start !== run.end) {
                run.weekType = run.start % 2 === 1 ? 'ODD' : 'EVEN';
            }
            runs.push(run);
            i = j + 1;
        }
        return runs;
    }

    // 节次文本 → 节次号数组："1-2" / "3-4节" / "1-2" 都认（上游是 jcs 优先、jc 兜底）
    function sectionsOf(row) {
        var source = text(row.jcs) || text(row.jc);
        source = source.replace(/节/g, '').replace(/\s+/g, '');
        var numbers = source.match(/[0-9]+/g) || [];
        var sections = [];
        for (var i = 0; i < numbers.length; i++) {
            var n = parseInt(numbers[i], 10);
            if (!isNaN(n) && n >= 1) sections.push(n);
        }
        return sections;
    }

    var order = [];
    var byCourse = {};
    var maxWeek = 0;
    var skippedRows = 0;

    for (var r = 0; r < rows.length; r++) {
        var row = rows[r] || {};
        var name = text(row.kcmc);
        var day = intOf(row.xqj);
        var sections = sectionsOf(row);
        var weeks = weeksOf(row.zcd);
        var startPeriod = sections.length ? sections[0] : 0;
        var endPeriod = sections.length ? sections[sections.length - 1] : 0;

        if (!name || !(day >= 1 && day <= 7) || !sections.length || !weeks.length ||
            endPeriod < startPeriod) {
            skippedRows++;
            continue;
        }

        // 教师、教室没有就让它是空的（上游写的「未知」「未排地点」在课表里会当成真名显示）
        var teacher = text(row.xm) || null;
        var location = text(row.cdmc) || text(row.cdbh) || null;
        var key = name + '\u0000' + (teacher || '');
        var course = byCourse[key];
        if (!course) {
            course = { name: name, teacher: teacher, note: null, blocks: [], seen: {} };
            byCourse[key] = course;
            order.push(key);
        }

        var runs = runsOf(weeks);
        for (var k = 0; k < runs.length; k++) {
            var run = runs[k];
            var blockKey = day + '|' + startPeriod + '|' + endPeriod + '|' + run.start + '|' +
                run.end + '|' + run.weekType + '|' + (location || '');
            if (course.seen[blockKey]) continue;
            course.seen[blockKey] = true;
            if (run.end > maxWeek) maxWeek = run.end;
            course.blocks.push({
                dayOfWeek: day,
                startPeriod: startPeriod,
                endPeriod: endPeriod,
                startWeek: run.start,
                endWeek: run.end,
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
    for (var c = 0; c < order.length; c++) {
        var built = byCourse[order[c]];
        courses.push({
            name: built.name,
            teacher: built.teacher,
            note: built.note,
            blocks: built.blocks
        });
    }

    // 总周数：正方不给校历周数，取课表里出现的最大周次（上游同口径），并如实写进 warnings
    var fromTerm = intOf(term.totalWeeks);
    var totalWeeks = fromTerm && fromTerm >= 1 ? fromTerm : maxWeek;
    if (totalWeeks < maxWeek) totalWeeks = maxWeek;
    if (totalWeeks > MAX_WEEK) totalWeeks = MAX_WEEK;
    if (!(totalWeeks >= 1)) totalWeeks = 20;

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
    if (!(fromTerm >= 1)) {
        warnings.push(
            '学期总周数取的是课表里出现的最大周次（' + totalWeeks + ' 周），不是校历周数，' +
            '如与实际不符可在学期管理里改'
        );
    }
    if (skippedRows > 0) {
        warnings.push(
            '有 ' + skippedRows + ' 行课表数据缺课程名 / 星期 / 节次 / 周次，已跳过：' +
            '教务数据不完整时会出现，如发现少课请反馈'
        );
    }
    if (droppedWeeks > 0) {
        warnings.push('有 ' + droppedWeeks + ' 处周次超出 1-' + MAX_WEEK + ' 周，已丢弃（教务给出的周次不正常）');
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
