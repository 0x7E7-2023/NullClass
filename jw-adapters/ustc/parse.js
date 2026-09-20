(function () {
    // 中国科学技术大学课表解析
    var rawInput = typeof __ncInput !== 'undefined' ? __ncInput : '{}';
    var data = JSON.parse(rawInput);
    var term = data.term || {};
    var lessonList = data.lessonList || [];
    var scheduleList = data.scheduleList || [];

    // 中国科学技术大学标准作息时间（13 节制）
    var PERIOD_TIMES = [
        { periodIndex: 1, start: '07:50', end: '08:35' },
        { periodIndex: 2, start: '08:40', end: '09:25' },
        { periodIndex: 3, start: '09:45', end: '10:30' },
        { periodIndex: 4, start: '10:35', end: '11:20' },
        { periodIndex: 5, start: '11:25', end: '12:10' },
        { periodIndex: 6, start: '14:00', end: '14:45' },
        { periodIndex: 7, start: '14:50', end: '15:35' },
        { periodIndex: 8, start: '15:55', end: '16:40' },
        { periodIndex: 9, start: '16:45', end: '17:30' },
        { periodIndex: 10, start: '17:35', end: '18:20' },
        { periodIndex: 11, start: '19:30', end: '20:15' },
        { periodIndex: 12, start: '20:20', end: '21:05' },
        { periodIndex: 13, start: '21:10', end: '21:55' }
    ];

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

    // 按日历日期运算，避免本地时区和夏令时把午夜挪到前一天。
    function epochDayOf(value) {
        var match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(text(value));
        if (!match) return null;
        var year = Number(match[1]);
        var month = Number(match[2]);
        var day = Number(match[3]);
        var millis = Date.UTC(year, month - 1, day);
        var date = new Date(millis);
        if (date.getUTCFullYear() !== year || date.getUTCMonth() !== month - 1 || date.getUTCDate() !== day) {
            return null;
        }
        return millis / 86400000;
    }

    function weekdayOf(epochDay) {
        return new Date(epochDay * 86400000).getUTCDay() || 7;
    }

    function isoOfDay(epochDay) {
        var date = new Date(epochDay * 86400000);
        return date.getUTCFullYear() + '-' + pad2(date.getUTCMonth() + 1) + '-' + pad2(date.getUTCDate());
    }

    function currentMondayIso() {
        var now = new Date();
        var today = epochDayOf(now.getFullYear() + '-' + pad2(now.getMonth() + 1) + '-' + pad2(now.getDate()));
        return isoOfDay(today - (weekdayOf(today) + 6) % 7);
    }

    var warnings = [];
    var firstDayEpoch = epochDayOf(term.firstDay);
    if (firstDayEpoch === null) {
        for (var f = 0; f < scheduleList.length; f++) {
            var candidate = scheduleList[f];
            var candidateDate = epochDayOf(candidate.date);
            var candidateWeek = intOf(candidate.weekIndex);
            if (candidateDate === null || !(candidateWeek >= 1)) continue;
            // USTC 一周从周日开始：该周周一在周日之后一天，不能回退六天。
            firstDayEpoch = candidateDate - (candidateWeek - 1) * 7 - (weekdayOf(candidateDate) % 7 - 1);
            warnings.push('学期起始日期未提供或无效，已按排课日期推算第一周周一为 ' + isoOfDay(firstDayEpoch) + '，请核对');
            break;
        }
    }
    if (firstDayEpoch === null) {
        firstDayEpoch = epochDayOf(currentMondayIso());
        warnings.push('无法取得学期起始日期，已按本周周一（' + isoOfDay(firstDayEpoch) + '）推算，请在学期管理中核对');
    }
    var firstDay = isoOfDay(firstDayEpoch);
    var firstWeekday = weekdayOf(firstDayEpoch);

    function getStartPeriod(time) {
        var t = intOf(time);
        if (!t) return 1;
        if (t <= 800) return 1;
        if (t <= 900) return 2;
        if (t <= 1000) return 3;
        if (t <= 1100) return 4;
        if (t <= 1200) return 5;
        if (t <= 1410) return 6;
        if (t <= 1500) return 7;
        if (t <= 1610) return 8;
        if (t <= 1700) return 9;
        if (t <= 1800) return 10;
        if (t <= 1945) return 11;
        if (t <= 2030) return 12;
        return 13;
    }

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

    function locationOf(room, custom) {
        if (room && typeof room === 'object') {
            var parts = [];
            if (room.building && room.building.nameZh) parts.push(text(room.building.nameZh));
            if (room.nameZh) parts.push(text(room.nameZh));
            if (parts.length) return parts.join(' ');
        }
        if (typeof room === 'string' && text(room)) return text(room);
        if (custom && text(custom)) return text(custom);
        return null;
    }

    var lessonMap = {};
    for (var lIdx = 0; lIdx < lessonList.length; lIdx++) {
        var l = lessonList[lIdx];
        var teachers = [];
        if (l.teachers && l.teachers.length) {
            teachers = l.teachers;
        } else if (l.teacherAssignmentList) {
            for (var t = 0; t < l.teacherAssignmentList.length; t++) {
                var tName = l.teacherAssignmentList[t].name ||
                    (l.teacherAssignmentList[t].person && l.teacherAssignmentList[t].person.nameZh);
                if (tName) teachers.push(text(tName));
            }
        }
        lessonMap[l.id] = {
            name: text(l.courseName || l.name),
            code: text(l.code),
            teacher: teachers.length ? teachers.join(',') : null
        };
    }

    var courseMap = {};
    var courseOrder = [];
    var inferredDates = 0;
    var invalidSchedules = 0;
    var beforeTerm = 0;
    var afterLimit = 0;

    for (var sIdx = 0; sIdx < scheduleList.length; sIdx++) {
        var s = scheduleList[sIdx];
        var date = epochDayOf(s.date);
        if (date === null) {
            var sourceDay = intOf(s.weekday);
            var sourceWeek = intOf(s.weekIndex);
            if (!(sourceDay >= 1 && sourceDay <= 7 && sourceWeek >= 1)) {
                invalidSchedules++;
                continue;
            }
            // 旧数据缺少日期时，仍需把教务的周日周界转换为导入学期的周界。
            date = firstDayEpoch + (sourceWeek - 1) * 7 + sourceDay % 7 - firstWeekday % 7;
            inferredDates++;
        }
        var day = weekdayOf(date);
        var week = Math.floor((date - firstDayEpoch) / 7) + 1;
        if (week < 1) {
            beforeTerm++;
            continue;
        }
        if (week > 30) {
            afterLimit++;
            continue;
        }

        var lInfo = lessonMap[s.lessonId] || { name: '未知课程', teacher: null };
        var cName = lInfo.name;
        var cTeacher = text(s.personName) || lInfo.teacher || null;
        var cKey = cName + '\u0000' + (cTeacher || '');

        if (!courseMap[cKey]) {
            courseMap[cKey] = {
                name: cName,
                teacher: cTeacher,
                slots: {},
                slotOrder: []
            };
            courseOrder.push(cKey);
        }

        var startP = getStartPeriod(s.startTime);
        var periods = intOf(s.periods) || 1;
        var endP = startP + periods - 1;
        var loc = locationOf(s.room, s.customPlace);
        var slotKey = [day, startP, endP, loc || ''].join('|');
        if (!courseMap[cKey].slots[slotKey]) {
            courseMap[cKey].slots[slotKey] = {
                day: day,
                startPeriod: startP,
                endPeriod: endP,
                location: loc,
                weeks: []
            };
            courseMap[cKey].slotOrder.push(slotKey);
        }
        if (courseMap[cKey].slots[slotKey].weeks.indexOf(week) === -1) {
            courseMap[cKey].slots[slotKey].weeks.push(week);
        }
    }

    var courses = [];
    var maxWeek = 0;
    for (var c = 0; c < courseOrder.length; c++) {
        var cObj = courseMap[courseOrder[c]];
        var blocks = [];
        for (var k = 0; k < cObj.slotOrder.length; k++) {
            var slot = cObj.slots[cObj.slotOrder[k]];
            slot.weeks.sort(function (a, b) { return a - b; });
            var runs = runsOf(slot.weeks);
            for (var r = 0; r < runs.length; r++) {
                var run = runs[r];
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
        courses.push({
            name: cObj.name,
            teacher: cObj.teacher,
            blocks: blocks
        });
    }

    var termName = text(term.name) || '当前学期';
    var totalWeeks = intOf(term.totalWeeks);
    if (!totalWeeks || totalWeeks < 1) {
        totalWeeks = maxWeek > 0 ? maxWeek : 18;
    }
    if (maxWeek > totalWeeks) {
        totalWeeks = maxWeek;
    }
    if (totalWeeks > 30) totalWeeks = 30;
    if (totalWeeks < 1) totalWeeks = 18;

    if (inferredDates) warnings.push('有 ' + inferredDates + ' 条排课缺少有效日期，已按教务周次和星期推算，请核对');
    if (invalidSchedules) warnings.push('有 ' + invalidSchedules + ' 条排课缺少有效日期、周次或星期，无法确定上课日期，已跳过');
    if (beforeTerm) warnings.push('有 ' + beforeTerm + ' 条排课早于学期起始日（' + firstDay + '），已跳过，请核对学期日期');
    if (afterLimit) warnings.push('有 ' + afterLimit + ' 条排课超出空课支持的 30 周范围，已跳过，请核对学期日期');

    var result = {
        specVersion: 1,
        kind: 'schedule',
        ocrAssisted: false,
        terms: [
            {
                name: termName,
                firstDay: firstDay,
                totalWeeks: totalWeeks,
                periodTimes: PERIOD_TIMES,
                courses: courses
            }
        ]
    };
    if (warnings.length) result.warnings = warnings;
    return JSON.stringify(result);
})()
