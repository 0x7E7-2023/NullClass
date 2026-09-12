(function () {
    // 郑州大学教务适配器（树维 for-std 平台）—— extract.js 的原始数据 → 空课课表载荷。
    // 移植自 shiguang_warehouse 的 ZZU/zzu.js
    //   https://github.com/XingHeYuZhuan/shiguang_warehouse  （MIT，作者 LilyCarry）
    //   上游快照：e62554a4034386b893bcd6813c7b2b64f8c730a3（2026-09-12）
    //
    // 上游怎么给数据：/student/for-std/course-table/semester/{id}/print-data 的
    // studentTableVms[0].activities 已经是「一条记录 = 一门课在一个星期几、一个节次段、
    // 一组周次」（weekIndexes 是整数数组），所以这里既不查表格也不解析周次文本，只做映射与合并：
    //   · 按「课名 + 教师」聚成一门课；同一门课同一节次段的记录按周次取并集
    //     （教务把前后半学期拆成两条时，合并后才是完整周次）
    //   · 周次数组 → 极大段：步长 1 每周、步长 2 单/双周、落单一周单算（一个数组可能切出多段 → 多个 block）
    //   · 教师名里的「(1)」「[2]」是教学班序号，按上游的做法去掉；空教师留空，不写「未知」
    //   · 教室：room，其次 building（上游是 room || building || "未知地点"；「未知地点」会被当成
    //     真教室显示，这里改成留空），顺带带上 campus（同平台的 CUP/CUPK 就是这么拼的）
    //
    // 与上游不同、且一定写进 warnings 的四件事（上游全部写死成常量）：
    //   · 开学日期：/student/ws/semester/get 的 startDate，按 weekStartOnSunday 回退到那一周的起始日
    //   · 作息表：print-data 的 timeTableLayout.courseUnitList（学校真实作息），没有才用上游内置的 12 节
    //   · 总周数：先按校历的起止日期数，课表里排得更晚就按课表抬上去（抬升必说）
    //   · 总周数下限：上游最后还有一道 Math.max(totalWeeks, 18)。保留这道下限，但它一旦真的
    //     改动了校历数出来的值就写进 warnings —— 用户看到的总周数与教务对不上时必须有个线索
    //
    // 解析不了的排课记录、看不懂的周次值一律计数进 warnings，不静默丢课；
    // 接口若回了记录总数而拿到的条数更少（分页/截断），也要出声。

    var data = JSON.parse(typeof __ncInput !== 'undefined' ? __ncInput : '{}');
    var term = data.term || {};
    var meta = data.semesterMeta || {};
    var semesters = data.semesters || [];
    var activities = data.activities || [];
    var courseUnitList = data.courseUnitList || [];
    var pageInfo = data.pageInfo || {};

    // 载荷校验的硬上限：超过 30 周的安排放不进载荷，只能跳过（会进 warnings）
    var MAX_WEEKS = 30;
    // 上游 zzu.js 最后的 Math.max(totalWeeks, 18)：校历数出来不足 18 周时的下限
    var MIN_TOTAL_WEEKS = 18;
    // 校历与课表都给不出周数时的默认值（上游也是 20）
    var DEFAULT_TOTAL_WEEKS = 20;

    // 上游 zzu.js 内置的郑州大学 12 节作息（第 1-4 节上午、5-8 节下午、9-12 节晚上）。
    // 只在教务接口没给 courseUnitList 时兜底 —— 它不是从教务取的，用了必须说。
    var PRESET_PERIOD_TIMES = [
        { periodIndex: 1, start: '08:00', end: '08:45' },
        { periodIndex: 2, start: '08:55', end: '09:40' },
        { periodIndex: 3, start: '10:10', end: '10:55' },
        { periodIndex: 4, start: '11:05', end: '11:50' },
        { periodIndex: 5, start: '14:00', end: '14:45' },
        { periodIndex: 6, start: '14:55', end: '15:40' },
        { periodIndex: 7, start: '16:10', end: '16:55' },
        { periodIndex: 8, start: '17:05', end: '17:50' },
        { periodIndex: 9, start: '19:00', end: '19:45' },
        { periodIndex: 10, start: '19:55', end: '20:40' },
        { periodIndex: 11, start: '20:50', end: '21:35' },
        { periodIndex: 12, start: '21:40', end: '22:25' }
    ];

    var DAY_NAMES = ['', '一', '二', '三', '四', '五', '六', '日'];

    // 复合键的分隔符。课程名和教师名里什么都可能出现，所以取一个不可能出现的控制字符；
    // 用 String.fromCharCode(0) 而不是源码里的转义序列写它 —— 本仓库的工具链会把那种转义
    // 落成真的 NUL 字节，文件就被 grep/diff 当二进制看（见 docs/jw-adapter-porting.md §3 第 2 步）。
    var KEY_SEP = String.fromCharCode(0);

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

    function isArray(value) {
        return Object.prototype.toString.call(value) === '[object Array]';
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

    // 两端**都算在内**的天数 → 周数（周一到周日是 1 周）。上游 zzu.js 用
    // ceil(相差天数/7)，在正好整周的边界上会少算一周，这里按含首尾的天数算。
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

    // 学年 + 月份 → 可读的学期名（教务连学期名都没给时才用，且会进 warnings）
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

    // 一个周次值：整数（或纯数字字符串）且 ≥ 1 才算。其余一律计数，不静默吞掉。
    function weekOf(value) {
        if (typeof value === 'number') {
            if (isFinite(value) && Math.floor(value) === value && value >= 1) return value;
            return null;
        }
        var raw = text(value);
        if (!/^[0-9]+$/.test(raw)) return null;
        var n = parseInt(raw, 10);
        return n >= 1 ? n : null;
    }

    // 周次数组 → 去重排序后的周次表。不是数组就返回空（由调用方按「缺周次」处理），
    // 数组里看不懂的值进 badWeeks 计数。
    function weeksOf(source) {
        var out = [];
        var seen = {};
        if (!isArray(source)) return out;
        for (var i = 0; i < source.length; i++) {
            var week = weekOf(source[i]);
            if (week === null) {
                badWeeks++;
                continue;
            }
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

    // 上游只认数组；这里连字符串也认（ZZU 的 teachers 两种形态都出现过），
    // 但字符串里的「(1)」和「[2]」同样要去掉，否则会被当成教师名的一部分。
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
        } else if (isArray(raw)) {
            for (i = 0; i < raw.length; i++) {
                var name = cleanTeacher(raw[i]);
                if (name && out.indexOf(name) === -1) out.push(name);
            }
        }
        return out.length ? out.join(',') : null;
    }

    // 教室：room 为主，其次 building，带上校区；都没有就留空
    //（上游写「未知地点」，那是会被当成真教室显示的假值）。
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
    if (!isArray(activities) || !activities.length) {
        throw new Error('教务没有返回任何排课记录：这个学期可能还没排课，或者教务系统换了接口');
    }

    var order = [];
    var byCourse = {};
    var maxWeek = 0;
    var maxPeriodUsed = 0;
    var badWeeks = 0;
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

        var weeks = weeksOf(act.weekIndexes);
        if (!weeks.length) {
            skip.weeks++;
            continue;
        }

        var teacher = teachersOf(act);
        var location = locationOf(act);
        var courseKey = name + KEY_SEP + (teacher || '');
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
        if (endPeriod > maxPeriodUsed) maxPeriodUsed = endPeriod;
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
    // 校历 → 课表 → 下限，每一步改动都留下痕迹（这个数字用户会直接看到）。
    var totalWeeksFromCalendar = firstDayEstimated ? null : weeksBetween(firstDay, endIso);
    var totalWeeksEstimated = !(totalWeeksFromCalendar >= 1);
    var totalWeeks = totalWeeksEstimated ? maxWeek : totalWeeksFromCalendar;
    var raisedBySchedule = !totalWeeksEstimated && maxWeek > totalWeeksFromCalendar;
    if (maxWeek > totalWeeks) totalWeeks = maxWeek;
    if (!(totalWeeks >= 1)) totalWeeks = DEFAULT_TOTAL_WEEKS;
    // 加下限之前真正用到的那个数 —— 下限真的改动了它时要如实报出来
    var totalWeeksBasis = totalWeeks;
    var flooredToMinimum = totalWeeks < MIN_TOTAL_WEEKS;
    if (flooredToMinimum) totalWeeks = MIN_TOTAL_WEEKS;
    if (totalWeeks > MAX_WEEKS) totalWeeks = MAX_WEEKS;

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

    var periodsEstimated = false;
    if (!periodTimes.length) {
        for (var d = 0; d < PRESET_PERIOD_TIMES.length; d++) {
            seenPeriods[PRESET_PERIOD_TIMES[d].periodIndex] = true;
            periodTimes.push(PRESET_PERIOD_TIMES[d]);
        }
        periodsEstimated = true;
    }

    // 课表里真正用到的节次必须都有上下课时间：教务的作息表给到第 6 节就没了、课表却排到第 12 节时，
    // 缺的那几节从内置作息表补出来（补不到就如实说，别让用户面对一节没有时间的课）。
    var periodsFilled = [];
    var uncovered = [];
    for (var p = 1; p <= maxPeriodUsed; p++) {
        if (seenPeriods[p] === true) continue;
        var preset = null;
        for (var q = 0; q < PRESET_PERIOD_TIMES.length; q++) {
            if (PRESET_PERIOD_TIMES[q].periodIndex === p) preset = PRESET_PERIOD_TIMES[q];
        }
        if (preset) {
            seenPeriods[p] = true;
            periodTimes.push(preset);
            periodsFilled.push(p);
        } else {
            uncovered.push(p);
        }
    }
    periodTimes.sort(function (a, b) { return a.periodIndex - b.periodIndex; });

    // ---------- 接口有没有把记录取全 ----------
    var reportedTotal = 0;
    var totalSources = [pageInfo.root, pageInfo.tableVm];
    for (var t = 0; t < totalSources.length; t++) {
        var source = totalSources[t];
        if (!source || typeof source !== 'object') continue;
        for (var key in source) {
            if (Object.prototype.hasOwnProperty.call(source, key) === false) continue;
            if (typeof source[key] === 'number' && source[key] > reportedTotal) reportedTotal = source[key];
        }
    }
    var truncated = reportedTotal > activities.length;

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
    if (badWeeks > 0) {
        warnings.push('排课记录里有 ' + badWeeks + ' 个周次值不是 1 以上的整数（教务数据格式变了），已忽略；' +
            '如发现某门课的周次不对，请到教务页面核对。');
    }
    if (truncated) {
        warnings.push('教务返回的记录总数（' + reportedTotal + '）多于本次拿到的排课记录（' + activities.length +
            ' 条），接口可能分页或截断，导入的课表可能不全，请到教务页面核对。');
    }
    if (outOfRange > 0) {
        warnings.push('有 ' + outOfRange + ' 条安排的周次超过 ' + MAX_WEEKS + ' 周的上限，已跳过。');
    }
    if (totalWeeksEstimated) {
        warnings.push('教务没有给出学期结束日期，学期总周数只能按课表里出现过的最晚周次推算为 ' + totalWeeksBasis +
            ' 周，请在学期管理里核对。');
    }
    if (raisedBySchedule) {
        warnings.push('教务给的学期起止日期（' + endIso + '）只能数出 ' + totalWeeksFromCalendar +
            ' 周，但课表里有安排落在第 ' + maxWeek + ' 周，学期总周数已按 ' + totalWeeks +
            ' 周导入，请在学期管理里核对。');
    }
    if (flooredToMinimum) {
        warnings.push('按现有信息学期总周数只数出 ' + totalWeeksBasis + ' 周，已按适配器内置的下限 ' +
            MIN_TOTAL_WEEKS + ' 周导入，请在学期管理里核对。');
    }
    if (periodsEstimated) {
        warnings.push('教务接口没有给出作息时间表，第 1~' + PRESET_PERIOD_TIMES.length +
            ' 节的上下课时间取自适配器内置的学校作息表，请在学期设置里核对。');
    }
    if (periodsFilled.length > 0) {
        warnings.push('教务给的作息表不完整，课表用到的第 ' + periodsFilled.join('、') +
            ' 节的上下课时间是用适配器内置的学校作息表补上的，请在学期设置里核对。');
    }
    if (uncovered.length > 0) {
        warnings.push('第 ' + uncovered.join('、') +
            ' 节在教务作息表与内置作息表里都没有上下课时间，课表里这些节次会显示不出时间，请在学期设置里补齐。');
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
