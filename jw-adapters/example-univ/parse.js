(function () {
    var data = JSON.parse(__ncInput);
    var rows = data.rows || [];
    var termName = (data.title && String(data.title).replace(/\s+/g, ' ').trim()) || '教务导入';
    var totalWeeks = data.totalWeeks || 20;
    var firstDay = data.firstDay || currentMondayIso();

    function pad(n) {
        return (n < 10 ? '0' : '') + n;
    }

    function currentMondayIso() {
        var now = new Date();
        var offset = (now.getDay() + 6) % 7;
        var monday = new Date(now.getTime() - offset * 86400000);
        return monday.getFullYear() + '-' + pad(monday.getMonth() + 1) + '-' + pad(monday.getDate());
    }

    function weekTypeOf(text) {
        if (text.indexOf('单') >= 0) return 'ODD';
        if (text.indexOf('双') >= 0) return 'EVEN';
        return 'ALL';
    }

    function parseWeeks(text) {
        if (!text) return null;
        var type = weekTypeOf(text);
        var range = /(\d{1,2})\s*[-—~至]\s*(\d{1,2})/.exec(text);
        if (range) {
            var start = parseInt(range[1], 10);
            var end = parseInt(range[2], 10);
            if (start >= 1 && end >= start && end <= totalWeeks) {
                return { startWeek: start, endWeek: end, weekType: type };
            }
        }
        var single = /(\d{1,2})\s*周/.exec(text);
        if (single) {
            var week = parseInt(single[1], 10);
            if (week >= 1 && week <= totalWeeks) {
                return { startWeek: week, endWeek: week, weekType: type };
            }
        }
        return null;
    }

    function parsePeriods(text) {
        if (!text) return null;
        var range = /(\d{1,2})\s*[-—~至]\s*(\d{1,2})/.exec(text);
        if (range) {
            var start = parseInt(range[1], 10);
            var end = parseInt(range[2], 10);
            if (start >= 1 && end >= start && end <= 30) {
                return { startPeriod: start, endPeriod: end };
            }
        }
        var single = /(\d{1,2})/.exec(text);
        if (single) {
            var period = parseInt(single[1], 10);
            if (period >= 1 && period <= 30) {
                return { startPeriod: period, endPeriod: period };
            }
        }
        return null;
    }

    var byName = {};
    var order = [];
    for (var i = 0; i < rows.length; i++) {
        var row = rows[i];
        if (!row || row.length < 4) continue;
        var name = String(row[0] || '').trim();
        if (!name || name === '课程名称') continue;

        var day = parseInt(row[2], 10);
        if (!(day >= 1 && day <= 7)) continue;

        var weeks = parseWeeks(row[1]) || { startWeek: 1, endWeek: totalWeeks, weekType: 'ALL' };
        var periods = parsePeriods(row[3]) || { startPeriod: 1, endPeriod: 1 };
        var location = row[4] ? String(row[4]).trim() : '';

        if (!byName[name]) {
            byName[name] = { name: name, teacher: null, note: null, blocks: [] };
            order.push(name);
        }
        byName[name].blocks.push({
            dayOfWeek: day,
            startPeriod: periods.startPeriod,
            endPeriod: periods.endPeriod,
            startWeek: weeks.startWeek,
            endWeek: weeks.endWeek,
            weekType: weeks.weekType,
            location: location ? location : null
        });
    }

    if (order.length === 0) {
        throw new Error('页面上没有识别到课程表格');
    }

    var courses = [];
    for (var j = 0; j < order.length; j++) courses.push(byName[order[j]]);

    return JSON.stringify({
        specVersion: 1,
        kind: 'schedule',
        ocrAssisted: false,
        terms: [
            {
                name: termName,
                firstDay: firstDay,
                totalWeeks: totalWeeks,
                courses: courses
            }
        ]
    });
})()
