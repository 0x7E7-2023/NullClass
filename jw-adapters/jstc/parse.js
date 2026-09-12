(function () {
    // 江苏旅游职业学院教务适配器（树维 for-std 平台）—— extract.js 的原始数据 → 空课课表载荷。
    // 移植自 shiguang_warehouse 的 JSTC/jstc_01.js
    //   https://github.com/XingHeYuZhuan/shiguang_warehouse  （MIT，作者 星河欲转）
    //   上游快照：e62554a（2026-09-12）
    //
    // 上游怎么给数据：/student/for-std/course-table/semester/{id}/print-data 的
    // studentTableVms[0].activities 已经是「一条记录 = 一门课在一个星期几、一个节次段、
    // 一组周次」，周次是显式数组（weekIndexes），所以这里不查表格，只做映射与合并：
    //   · 按「课名 + 教师」聚成一门课；同一门课同一节次段的记录按周次取并集
    //     （教务会把前后半学期拆成两条，合并后才是完整周次）
    //   · 周次数组 → 极大段：步长 1 每周、步长 2 单/双周、落单一周单算（一个数组可能切出多段 → 多个 block）
    //   · 教师名里的「(1)」「[2]」是教学班序号，按上游的做法去掉；空教师留空，不写「未知」
    //
    // 上游写死、这里改成能取就取的两处（取不到才回退，且回退必进 warnings）：
    //   · 开学日期：/student/ws/semester/get 的 startDate，按 weekStartOnSunday 回退到那一周的起始日
    //   · 作息表：print-data 的 timeTableLayout.courseUnitList（学校真实作息），没有才用上游内置的 11 节
    //
    // 解析不了的排课记录一律计数进 warnings，不静默丢课；
    // 覆盖了教务给的口径也一样要说 —— 课表周次比校历更晚时抬高的总周数会进 warnings。

    var data = JSON.parse(typeof __ncInput !== 'undefined' ? __ncInput : '{}');
    var term = data.term || {};
    var meta = data.semesterMeta || {};
    var semesters = data.semesters || [];
    var activities = data.activities || [];
    var courseUnitList = data.courseUnitList || [];

    // 载荷校验的硬上限：超过 30 周的安排放不进载荷，只能跳过（会进 warnings）
    var MAX_WEEKS = 30;

    // 上游内置的学校作息表（jstc_01.js 的 presetTimeSlots，11 节）。
    // 只在教务接口没给 courseUnitList 时兜底 —— 它不是从教务取的，用了必须说。
    var PRESET_PERIOD_TIMES = [
        { periodIndex: 1, start: '08:00', end: '08:40' },
        { periodIndex: 2, start: '08:50', end: '09:30' },
        { periodIndex: 3, start: '09:50', end: '10:30' },
        { periodIndex: 4, start: '10:40', end: '11:20' },
        { periodIndex: 5, start: '11:30', end: '12:10' },
        { periodIndex: 6, start: '14:00', end: '14:40' },
        { periodIndex: 7, start: '14:50', end: '15:30' },
        { periodIndex: 8, start: '15:50', end: '16:30' },
        { periodIndex: 9, start: '16:40', end: '17:20' },
        { periodIndex: 10, start: '19:00', end: '19:40' },
        { periodIndex: 11, start: '19:50', end: '20:30' }
    ];

    var DAY_NAMES = ['', '一', '二', '三', '四', '五', '六', '日'];

    var warnings = [];

    // ---------- 小工具 ----------
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

    function isoOfDate(date) {
        return date.getFullYear() + '-' + pad2(date.getMonth() + 1) + '-' + pad2(date.getDate());
    }

    // "2025-09-01" / "2025-9-1" / "2025/09/01 00:00:00" / "2025-09-01T00:00:00" 都认；
    // 先用正则取日期部分，避免时区把日期挪一天
    function isoOf(value) {
        var raw = text(value);
        if (!raw) return null;
        var m = /^(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})/.exec(raw);
        if (m) return m[1] + '-' + pad2(intOf(m[2])) + '-' + pad2(intOf(m[3]));
        var parsed = new Date(raw);
        if (isNaN(parsed.getTime())) return null;
        return isoOfDate(parsed);
    }

    function partsOf(iso) {
        var m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(text(iso));
        if (!m) return null;
        return [intOf(m[1]), intOf(m[2]), intOf(m[3])];
    }

    // 1=周一 … 7=周日（教务的 firstDayOfWeek 口径）；日期不合法（2025-02-31、2025-13-01）返回 null
    function weekdayOf(iso) {
        var p = partsOf(iso);
        if (!p) return null;
        var date = new Date(p[0], p[1] - 1, p[2]);
        if (isNaN(date.getTime())) return null;
        if (date.getFullYear() !== p[0] || date.getMonth() !== p[1] - 1 || date.getDate() !== p[2]) return null;
        var jsDay = date.getDay();
        return jsDay === 0 ? 7 : jsDay;
    }

    function minusDays(iso, days) {
        var p = partsOf(iso);
        if (!p) return null;
        var date = new Date(p[0], p[1] - 1, p[2]);
        date.setDate(date.getDate() - days);
        return isoOfDate(date);
    }

    // 上游 getWeekIndexAtDate 的口径：先把开学日回退到 firstDayOfWeek 那一天，再从那里数周次。
    // 所以载荷的 firstDay 是「回退后的那一天」，不是教务给的 startDate 本身。
    function weekStartOf(iso, firstDayOfWeek) {
        var weekday = weekdayOf(iso);
        if (weekday === null) return null;
        return minusDays(iso, (weekday - firstDayOfWeek + 7) % 7);
    }

    function todayWeekStart(firstDayOfWeek) {
        var today = isoOfDate(new Date());
        var weekday = weekdayOf(today);
        return minusDays(today, (weekday - firstDayOfWeek + 7) % 7);
    }

    function weeksBetween(startIso, endIso) {
        var a = partsOf(startIso);
        var b = partsOf(endIso);
        if (!a || !b) return null;
        var days = Math.round(
            (new Date(b[0], b[1] - 1, b[2]).getTime() - new Date(a[0], a[1] - 1, a[2]).getTime()) / 86400000
        );
        if (!(days >= 0)) return null;
        return Math.ceil((days + 1) / 7);
    }

    // 星期几 + 学年 → 可读的学期名（教务连学期名都没给时才用，且会进 warnings）
    function inferTermName(iso) {
        var p = partsOf(iso);
        if (!p) return null;
        var year = p[0];
        if (p[1] >= 8) return year + '-' + (year + 1) + '学年第一学期';
        if (p[1] >= 2) return (year - 1) + '-' + year + '学年第二学期';
        return (year - 1) + '-' + year + '学年第一学期';
    }

    // "08:00" / "8:00" / "0800" / 800 / "080000" → "HH:mm"；认不出返回 null
    function fmtTime(value) {
        var raw = text(value);
        if (!raw) return null;
        var withColon = /^(\d{1,2}):(\d{2})/.exec(raw);
        var digits;
        if (withColon) {
            digits = pad2(intOf(withColon[1])) + withColon[2];
        } else {
            digits = raw.replace(/[^0-9]/g, '');
            if (digits.length === 3) digits = '0' + digits;
            if (digits.length === 6) digits = digits.slice(0, 4);
            if (digits.length !== 4) return null;
        }
        var hour = intOf(digits.slice(0, 2));
        var minute = intOf(digits.slice(2, 4));
        if (!(hour >= 0 && hour <= 23) || !(minute >= 0 && minute <= 59)) return null;
        return pad2(hour) + ':' + pad2(minute);
    }

    function minutesOf(hhmm) {
        return intOf(hhmm.slice(0, 2)) * 60 + intOf(hhmm.slice(3, 5));
    }

    function uniqueSorted(source) {
        var out = [];
        var seen = {};
        if (!source || typeof source.length !== 'number') return out;
        for (var i = 0; i < source.length; i++) {
            var week = intOf(source[i]);
            if (week === null || week < 1) continue;
            if (seen[week] === true) continue;
            seen[week] = true;
            out.push(week);
        }
        out.sort(function (a, b) { return a - b; });
        return out;
    }

    // 周次集合 → 极大段。步长 1 = 每周，步长 2 = 单/双周，落单的周单算（weekType=ALL）。
    // 步长既不是 1 也不是 2（例如每 3 周一次）会落成一堆单周段 —— 语义仍然正确，只是块多。
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

    // 教师名里的「(1)」「[2]」「（3）」是教学班序号，去掉（上游的做法）
    function cleanTeacher(value) {
        return text(String(value === null || value === undefined ? '' : value)
            .replace(/\(\d+\)/g, '')
            .replace(/\[\d+\]/g, '')
            .replace(/（\d+）/g, ''));
    }

    function teachersOf(act) {
        var raw = act.teachers;
        var out = [];
        var i;
        if (typeof raw === 'string') {
            var parts = raw.split(/[、,，;\/]/);
            for (i = 0; i < parts.length; i++) {
                var one = cleanTeacher(parts[i]);
                if (one) out.push(one);
            }
        } else if (raw && typeof raw.length === 'number') {
            for (i = 0; i < raw.length; i++) {
                var name = cleanTeacher(raw[i]);
                if (name && out.indexOf(name) === -1) out.push(name);
            }
        }
        return out.length ? out.join(',') : null;
    }

    // 教室：room 为主（上游只读 room），带上校区；空着留空（写「未知」会被当成真名显示）
    function locationOf(act) {
        var room = text(act.room) || text(act.building);
        var campus = text(act.campus);
        if (room && campus && room.indexOf(campus) === -1) return campus + ' ' + room;
        if (room) return room;
        if (campus) return campus;
        return null;
    }

    // ---------- 开学日期 ----------
    var startIso = isoOf(meta.startDate);
    var endIso = isoOf(meta.endDate);
    var firstDayOfWeek = intOf(meta.firstDayOfWeek);
    if (!(firstDayOfWeek >= 1 && firstDayOfWeek <= 7)) {
        firstDayOfWeek = meta.weekStartOnSunday === true ? 7 : 1;
    }

    var firstDayEstimated = false;
    var firstDay;
    if (startIso) {
        firstDay = weekStartOf(startIso, firstDayOfWeek);
    }
    if (!firstDay) {
        firstDay = todayWeekStart(firstDayOfWeek);
        firstDayEstimated = true;
    }

    // ---------- 学期名 ----------
    var termName = text(term.label) || text(meta.nameZh) || text(meta.name);
    var nameEstimated = false;
    if (!termName) {
        termName = inferTermName(startIso) || '教务导入的学期';
        nameEstimated = true;
    }

    // ---------- 排课记录 → 课程 ----------
    if (!activities.length) {
        throw new Error('教务没有返回任何排课记录：这个学期可能还没排课，或者教务系统换了接口');
    }

    var order = [];
    var byCourse = {};
    var maxWeek = 0;
    var skip = { name: 0, day: 0, unit: 0, weeks: 0 };
    var outOfRange = 0;

    for (var i = 0; i < activities.length; i++) {
        var act = activities[i] || {};

        var name = text(act.courseName);
        if (!name) {
            skip.name++;
            continue;
        }

        var day = intOf(act.weekday);
        if (!(day >= 1 && day <= 7)) {
            skip.day++;
            continue;
        }

        var startPeriod = intOf(act.startUnit);
        var endPeriod = intOf(act.endUnit);
        if (!(startPeriod >= 1) || !(endPeriod >= startPeriod)) {
            skip.unit++;
            continue;
        }

        var weeks = uniqueSorted(act.weekIndexes);
        if (!weeks.length) {
            skip.weeks++;
            continue;
        }

        var teacher = teachersOf(act);
        var location = locationOf(act);
        var courseKey = name + '\u0000' + (teacher || '');
        if (!byCourse[courseKey]) {
            byCourse[courseKey] = { name: name, teacher: teacher, slots: {}, slotOrder: [] };
            order.push(courseKey);
        }
        var course = byCourse[courseKey];

        var slotKey = [day, startPeriod, endPeriod, location || ''].join('|');
        if (!course.slots[slotKey]) {
            course.slots[slotKey] = {
                day: day,
                startPeriod: startPeriod,
                endPeriod: endPeriod,
                location: location,
                weeks: []
            };
            course.slotOrder.push(slotKey);
        }
        var slotWeeks = course.slots[slotKey].weeks;
        for (var w = 0; w < weeks.length; w++) {
            if (slotWeeks.indexOf(weeks[w]) === -1) slotWeeks.push(weeks[w]);
        }
    }

    if (!order.length) {
        throw new Error('教务返回了 ' + activities.length +
            ' 条排课记录，但都缺少课程名 / 星期 / 节次 / 周次，解析不出任何课程（教务数据格式可能变了）');
    }

    var courses = [];
    for (var c = 0; c < order.length; c++) {
        var courseData = byCourse[order[c]];
        var blocks = [];
        for (var s = 0; s < courseData.slotOrder.length; s++) {
            var slot = courseData.slots[courseData.slotOrder[s]];
            slot.weeks.sort(function (a, b) { return a - b; });
            var runs = runsOf(slot.weeks);
            for (var r = 0; r < runs.length; r++) {
                var run = runs[r];
                if (run.end > MAX_WEEKS) {
                    outOfRange++;
                    continue;
                }
                if (run.end > maxWeek) maxWeek = run.end;
                blocks.push({
                    dayOfWeek: slot.day,
                    startPeriod: slot.startPeriod,
                    endPeriod: slot.endPeriod,
                    startWeek: run.start,
                    endWeek: run.end,
                    weekType: run.weekType,
                    location: slot.location
                });
            }
        }
        blocks.sort(function (a, b) {
            if (a.dayOfWeek !== b.dayOfWeek) return a.dayOfWeek - b.dayOfWeek;
            if (a.startPeriod !== b.startPeriod) return a.startPeriod - b.startPeriod;
            if (a.endPeriod !== b.endPeriod) return a.endPeriod - b.endPeriod;
            return a.startWeek - b.startWeek;
        });
        courses.push({
            name: courseData.name,
            teacher: courseData.teacher,
            note: null,
            blocks: blocks
        });
    }

    // ---------- 总周数 ----------
    var totalWeeksFromDates = firstDayEstimated ? null : weeksBetween(firstDay, endIso);
    var totalWeeksEstimated = !(totalWeeksFromDates >= 1);
    var totalWeeks = totalWeeksEstimated ? 0 : totalWeeksFromDates;
    // 课表里有比校历更晚的周次 → 校历给短了，按课表抬上去（抬升本身合理，但推翻了教务给的值，
    // 必须和开学日、作息表来源一样写进 warnings，否则用户看到的周数与教务不一致却没有任何线索）。
    var totalWeeksRaised = !totalWeeksEstimated && maxWeek > totalWeeksFromDates;
    if (maxWeek > totalWeeks) totalWeeks = maxWeek;
    if (totalWeeks > MAX_WEEKS) totalWeeks = MAX_WEEKS;
    if (!(totalWeeks >= 1)) totalWeeks = 20;

    // ---------- 作息表 ----------
    var periodTimes = [];
    var seenPeriods = {};
    for (var u = 0; u < courseUnitList.length; u++) {
        var unit = courseUnitList[u] || {};
        var periodIndex = intOf(unit.indexNo || unit.index || unit.periodIndex);
        var periodStart = fmtTime(unit.startTime || unit.start);
        var periodEnd = fmtTime(unit.endTime || unit.end);
        if (!(periodIndex >= 1) || !periodStart || !periodEnd) continue;
        if (minutesOf(periodStart) >= minutesOf(periodEnd)) continue;
        if (seenPeriods[periodIndex] === true) continue;
        seenPeriods[periodIndex] = true;
        periodTimes.push({ periodIndex: periodIndex, start: periodStart, end: periodEnd });
    }
    periodTimes.sort(function (a, b) { return a.periodIndex - b.periodIndex; });
    var periodsEstimated = false;
    if (!periodTimes.length) {
        periodTimes = PRESET_PERIOD_TIMES;
        periodsEstimated = true;
    }

    // ---------- 核对提示 ----------
    if (term.pickedBy === 'first') {
        warnings.push('教务页面没有标出当前学期，已取学期列表里的第一个「' + termName +
            '」；如果不对，请在教务页面上切到目标学期后重新提取。');
    }
    if (semesters.length > 1) {
        warnings.push('本次只导入了「' + termName + '」（教务页面上当前打开的学期）；学期列表里还有 ' +
            (semesters.length - 1) + ' 个学期，需要哪一个就在教务页面上切过去再点「提取课表」。');
    }
    if (firstDayEstimated) {
        warnings.push('开学日期无法从教务获取（学期接口没有返回 startDate），已按「最近的周' +
            DAY_NAMES[firstDayOfWeek] + '」推算为 ' + firstDay +
            '；开学日期决定「现在第几周」，请在学期管理里核对。');
    }
    var skippedTotal = skip.name + skip.day + skip.unit + skip.weeks;
    if (skippedTotal > 0) {
        var reasons = [];
        if (skip.name) reasons.push('缺课程名 ' + skip.name + ' 条');
        if (skip.day) reasons.push('缺星期 ' + skip.day + ' 条');
        if (skip.unit) reasons.push('缺节次 ' + skip.unit + ' 条');
        if (skip.weeks) reasons.push('缺周次 ' + skip.weeks + ' 条');
        warnings.push('教务返回的排课记录里有 ' + skippedTotal + ' 条解析不了，已跳过（' +
            reasons.join('、') + '）；如发现少了课，请到教务页面核对原课表。');
    }
    if (outOfRange > 0) {
        warnings.push('有 ' + outOfRange + ' 条安排的周次超过 ' + MAX_WEEKS + ' 周的上限，已跳过。');
    }
    if (totalWeeksEstimated) {
        warnings.push('教务没有给出学期结束日期，学期总周数按课程里出现的最大周次取为 ' + totalWeeks +
            ' 周，请在学期管理里核对。');
    }
    if (totalWeeksRaised) {
        warnings.push('教务给的学期起止日期（' + endIso + '）只能数出 ' + totalWeeksFromDates +
            ' 周，但课表里有安排落在第 ' + maxWeek + ' 周，学期总周数已按 ' + totalWeeks +
            ' 周导入，请在学期管理里核对。');
    }
    if (periodsEstimated) {
        warnings.push('教务接口没有给出作息时间表，第 1~' + PRESET_PERIOD_TIMES.length +
            ' 节的上下课时间取自适配器内置的学校作息表，请在学期设置里核对。');
    }
    if (nameEstimated) {
        warnings.push('教务没有给出学期名称，已写成「' + termName + '」，可以在学期管理里改成你想要的名字。');
    }

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
                periodTimes: periodTimes,
                courses: courses
            }
        ]
    });
})()
