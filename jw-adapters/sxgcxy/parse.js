(function () {
    // 山西工程职业学院 · 超星（chaoxing）教务适配器 —— 纯转换段（第二步）。
    //
    // 移植自 shiguang_warehouse 的 SXGCXY/sxgcxy_01.js
    //   https://github.com/XingHeYuZhuan/shiguang_warehouse  （MIT，作者 星河欲转）
    // 移植改动：
    //   ① 上游 parseJsonData / parseWeeks / extractAnchorText / cleanTeacherName 是这一段的
    //      全部内容，逻辑照搬，只把周次数组换成空课的 (startWeek, endWeek, weekType) 段；
    //   ② 课名 / 教师 / 教室的 HTML 清洗比上游稳一点：去掉全部标签，而不是只取第一个 >…< 片段；
    //   ③ 没有教室时写 null（上游写「待定」——那是教务的占位符，不是教室名，会被当真的显示出来）；
    //   ④ 排序不用 localeCompare：Node 与 CI 的 Rhino 对中文的排序结果可能不同，
    //      改成按码位比较，保证同一份输入在两边得到逐字节一致的输出；
    //   ⑤ 开学日、学期总周数、校区作息都拿不到现成的 —— 推算的写进 warnings，不静默；
    //      解析不了的行也**计数上报**，不悄悄丢。
    //
    // 输入：extract.js 的原样输出（fixtures/basic.extracted.json）。
    // 输出：空课课表载荷（specVersion 1）。
    var data = JSON.parse(typeof __ncInput === 'undefined' ? '{}' : __ncInput);
    var rows = data.rows;
    var term = data.term || {};

    // 空课校验要求 startWeek/endWeek ≤ totalWeeks ≤ 30
    var MAX_WEEK = 30;
    // 学期总周数拿不到时用的下限（高职院校多为 18–20 周，取 20 不至于少显示一周）
    var MIN_TOTAL_WEEKS = 20;

    // 校区作息时间（教务处公布的 10 节制），与上游 TangHuaiTimeSlots / LongTanTimeSlots 一致。
    // 代号由 extract.js 问用户后带过来；代号缺失（用户取消 / 提问桥不可用）时**不写**节次时间，
    // 由应用补默认节次表，并在 warnings 里说明 —— 两个校区只差 20 分钟，但猜错了整学期都不对。
    var CAMPUSES = {
        tanghuai: {
            label: '唐槐校区',
            periodTimes: [
                { periodIndex: 1, start: '08:20', end: '09:05' },
                { periodIndex: 2, start: '09:05', end: '09:50' },
                { periodIndex: 3, start: '10:00', end: '10:45' },
                { periodIndex: 4, start: '10:45', end: '11:30' },
                { periodIndex: 5, start: '13:40', end: '14:25' },
                { periodIndex: 6, start: '14:25', end: '15:10' },
                { periodIndex: 7, start: '15:20', end: '16:05' },
                { periodIndex: 8, start: '16:05', end: '16:50' },
                { periodIndex: 9, start: '17:30', end: '18:15' },
                { periodIndex: 10, start: '18:15', end: '19:00' }
            ]
        },
        longtan: {
            label: '龙潭校区',
            periodTimes: [
                { periodIndex: 1, start: '08:00', end: '08:45' },
                { periodIndex: 2, start: '08:45', end: '09:30' },
                { periodIndex: 3, start: '10:00', end: '10:45' },
                { periodIndex: 4, start: '10:45', end: '11:30' },
                { periodIndex: 5, start: '14:30', end: '15:15' },
                { periodIndex: 6, start: '15:15', end: '16:00' },
                { periodIndex: 7, start: '16:30', end: '17:15' },
                { periodIndex: 8, start: '17:15', end: '18:00' },
                { periodIndex: 9, start: '19:00', end: '19:45' },
                { periodIndex: 10, start: '19:45', end: '20:30' }
            ]
        }
    };

    function pad2(value) {
        return (value < 10 ? '0' : '') + value;
    }

    function isoOf(date) {
        return date.getFullYear() + '-' + pad2(date.getMonth() + 1) + '-' + pad2(date.getDate());
    }

    // "2026-2027-1" → { startYear: 2026, semester: 1 }
    function parseTermCode(code) {
        var matched = /^(\d{4})-(\d{4})-([12])$/.exec(String(code === null || code === undefined ? '' : code));
        if (!matched) return null;
        return { startYear: parseInt(matched[1], 10), semester: parseInt(matched[3], 10) };
    }

    function termNameOf(info) {
        return info.startYear + '-' + (info.startYear + 1) + ' 学年' +
            (info.semester === 1 ? '第一学期' : '第二学期');
    }

    function nameOfCode(code) {
        var info = parseTermCode(code);
        return info ? termNameOf(info) : String(code === null || code === undefined ? '未知学期' : code);
    }

    // 开学日：教务接口不提供校历（上游脚本里也没有这个值），只能推算 ——
    // 第一学期按「9 月 1 日之后的第一个周一」，第二学期按「2 月 20 日之后的第一个周一」。
    // 推算值一律写进 warnings：它决定「现在第几周」，猜错了整学期的课都错位。
    function firstDayOf(info) {
        var spring = info.semester === 2;
        var anchor = new Date(spring ? info.startYear + 1 : info.startYear, spring ? 1 : 8, spring ? 20 : 1);
        var shift = (8 - anchor.getDay()) % 7;
        return isoOf(new Date(anchor.getFullYear(), anchor.getMonth(), anchor.getDate() + shift));
    }

    // 课名 / 教师 / 教室在超星接口里是 HTML 片段。先去标签再去首尾空白：
    // 块级标签（br/p/div/li/tr/td）换成空格，其余（span/a/b 之类）直接去掉 —— 否则
    // <span>高等</span><span>数学</span> 会被拼成「高等 数学」。
    function htmlText(value) {
        if (value === null || value === undefined) return '';
        var text = String(value)
            .replace(/<\s*br\s*\/?\s*>/gi, ' ')
            .replace(/<\s*\/\s*(p|div|li|tr|td|th|h[1-6])\s*>/gi, ' ')
            .replace(/<[^>]*>/g, '');
        text = text
            .replace(/&nbsp;/gi, ' ')
            .replace(/&amp;/gi, '&')
            .replace(/&lt;/gi, '<')
            .replace(/&gt;/gi, '>')
            .replace(/&quot;/gi, '"')
            .replace(/&#39;/g, "'");
        return text.replace(/\s+/g, ' ').trim();
    }

    // 教师名里的职称在括号里（"王建国（副教授）"），上游同款处理：连括号一起去掉。
    // 空的就写 null —— 空着比写「未知」好（「未知」会被当成真名显示）。
    function teacherOf(value) {
        var text = htmlText(value).replace(/（[^）]*）/g, '').replace(/\([^)]*\)/g, '');
        text = text.replace(/\s+/g, ' ').trim();
        return text || null;
    }

    // 只认纯整数："3" / "03" 可以，"3-4" / "第3节" / "" 都不行 ——
    // 与其用 parseInt 把 "3-4" 悄悄读成 3，不如让它算「这行读不了」并计数上报。
    function intOf(value) {
        if (value === null || value === undefined) return null;
        var text = String(value).replace(/\s+/g, '');
        if (!/^[+-]?\d{1,3}$/.test(text)) return null;
        var parsed = parseInt(text, 10);
        return isNaN(parsed) ? null : parsed;
    }

    // 周次串。上游的注释说超星直接给逗号分隔的数字（"1,2,3"），这里多认两种写法
    // （"1-16" 区间、"1-16周(单)"），多认一种就少丢一门课。
    // 超出 1..30 的周次数字会被丢掉，但**丢了多少条要计数上报**（见 droppedWeeks）。
    var droppedWeeks = 0;

    function weeksOf(source) {
        var weeks = [];
        // 先把区间符两侧的空白吃掉（"1 - 16" 与 "1-16" 一样看待），再按逗号 / 空白切段
        var raw = String(source === null || source === undefined ? '' : source);
        var segments = raw.replace(/\s*([-—~～至])\s*/g, '$1').split(/[,，、;；\s]+/);
        for (var i = 0; i < segments.length; i++) {
            var segment = segments[i];
            if (!segment) continue;
            var onlyOdd = segment.indexOf('单') >= 0;
            var onlyEven = segment.indexOf('双') >= 0;
            var range = /(\d{1,3})\s*[-—~～至]\s*(\d{1,3})/.exec(segment);
            if (range) {
                var start = parseInt(range[1], 10);
                var end = parseInt(range[2], 10);
                // 上限 40 步只是防止 "1-2026" 这种脏数据把数组撑爆
                for (var week = start; week <= end && week < start + 40; week++) {
                    if (onlyOdd && week % 2 === 0) continue;
                    if (onlyEven && week % 2 === 1) continue;
                    weeks.push(week);
                }
                continue;
            }
            var single = /(\d{1,3})/.exec(segment);
            if (single) weeks.push(parseInt(single[1], 10));
        }

        var seen = {};
        var unique = [];
        for (var j = 0; j < weeks.length; j++) {
            var value = weeks[j];
            if (value < 1 || value > MAX_WEEK) {
                droppedWeeks++;
                continue;
            }
            if (seen[value]) continue;
            seen[value] = true;
            unique.push(value);
        }
        unique.sort(function (a, b) { return a - b; });
        return unique;
    }

    // 周次集合 → 极大段。步长 1 视作每周，步长 2 视作单 / 双周。
    // 一个上游课程可能切出多个段 —— 那就写成该课程的多个 block（我们的 blocks 本来就是列表）。
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

    // 码位比较（不用 localeCompare：Node 与 Rhino 对中文的排序可能不同，会毁掉 fixture 比对）
    function compareText(left, right) {
        var a = left === null || left === undefined ? '' : String(left);
        var b = right === null || right === undefined ? '' : String(right);
        if (a === b) return 0;
        return a < b ? -1 : 1;
    }

    if (!rows || !Array.isArray(rows)) {
        throw new Error('提取数据里没有课表行（rows）：教务系统的返回格式可能变了');
    }

    var warnings = [];
    var items = [];
    var skipped = 0;

    for (var index = 0; index < rows.length; index++) {
        var row = rows[index] || {};
        var name = htmlText(row.kcmc);
        var day = intOf(row.xingqi);
        var section = intOf(row.djc);
        var weeks = weeksOf(row.zcstr);

        if (!name || !(day >= 1 && day <= 7) || !(section >= 1) || !weeks.length) {
            skipped++;
            continue;
        }

        items.push({
            name: name,
            teacher: teacherOf(row.tmc),
            location: htmlText(row.croommc) || null,
            day: day,
            section: section,
            weeks: weeks,
            weeksKey: weeks.join(','),
            order: index
        });
    }

    // 上游的排序：星期 → 周次 → 课名 → 教师 → 教室 → 节次，保证同一门课的连堂记录相邻。
    // 最后按原始行序兜底，让排序结果与引擎的排序稳定性无关。
    items.sort(function (a, b) {
        return (a.day - b.day) ||
            compareText(a.weeksKey, b.weeksKey) ||
            compareText(a.name, b.name) ||
            compareText(a.teacher, b.teacher) ||
            compareText(a.location, b.location) ||
            (a.section - b.section) ||
            (a.order - b.order);
    });

    // 连堂合并：星期 / 课名 / 教师 / 教室 / 周次都相同、且节次连续 → 合成一条安排
    var merged = [];
    var i = 0;
    while (i < items.length) {
        var current = items[i];
        var endSection = current.section;
        var j = i + 1;
        while (j < items.length) {
            var next = items[j];
            if (next.day === current.day &&
                next.name === current.name &&
                next.teacher === current.teacher &&
                next.location === current.location &&
                next.weeksKey === current.weeksKey &&
                next.section === endSection + 1) {
                endSection = next.section;
                j++;
            } else {
                break;
            }
        }
        merged.push({
            name: current.name,
            teacher: current.teacher,
            location: current.location,
            day: current.day,
            startPeriod: current.section,
            endPeriod: endSection,
            weeks: current.weeks
        });
        i = j;
    }

    // 课程归并：同名同教师的多条安排进同一个 courses[].blocks
    var order = [];
    var byCourse = {};
    var maxWeek = 0;

    for (var m = 0; m < merged.length; m++) {
        var item = merged[m];
        // 键里夹一个 NUL 作分隔（课名里不会出现），省得「课名 + 教师」直接拼起来撞键
        var key = item.name + '\u0000' + (item.teacher || '');
        if (!byCourse[key]) {
            byCourse[key] = { name: item.name, teacher: item.teacher, note: null, blocks: [] };
            order.push(key);
        }
        var runs = runsOf(item.weeks);
        for (var r = 0; r < runs.length; r++) {
            var run = runs[r];
            if (run.end > maxWeek) maxWeek = run.end;
            byCourse[key].blocks.push({
                dayOfWeek: item.day,
                startPeriod: item.startPeriod,
                endPeriod: item.endPeriod,
                startWeek: run.start,
                endWeek: run.end,
                weekType: run.weekType,
                location: item.location
            });
        }
    }

    if (!order.length) {
        throw new Error('没有解析到任何课程：收到了 ' + rows.length + ' 行课表数据，' +
            '但都缺少星期 / 节次 / 周次（教务系统的数据格式可能变了）');
    }

    var info = parseTermCode(term.code);
    if (!info) {
        throw new Error('学期代码「' + String(term.code) + '」不是「2026-2027-1」这种格式，无法确定学期与开学日');
    }

    var courses = [];
    for (var c = 0; c < order.length; c++) courses.push(byCourse[order[c]]);

    var totalWeeks = maxWeek > MIN_TOTAL_WEEKS ? maxWeek : MIN_TOTAL_WEEKS;

    // ---- 核对提示：凡是推算的、跳过的、猜的，一律在这里说清楚 ----
    var attempts = term.attempts && term.attempts.length ? term.attempts : [term.code];
    if (attempts.length > 1 && attempts[0] !== term.code) {
        warnings.push('当前学期「' + nameOfCode(attempts[0]) + '」没有课表数据，已改为导入上一学期「' +
            nameOfCode(term.code) + '」，请确认这是你要的学期。');
    }

    warnings.push('教务系统不提供校历，开学日期「' + firstDayOf(info) + '」是按' +
        (info.semester === 2 ? '「2 月 20 日之后的第一个周一」' : '「9 月 1 日之后的第一个周一」') +
        '推算的，它决定「现在第几周」，请在学期管理里核对。');

    // 用 hasOwnProperty 取，免得 "constructor" / "__proto__" 这类值从原型上摸出个真值来
    var campusCode = data.campus === null || data.campus === undefined ? '' : String(data.campus);
    var campus = Object.prototype.hasOwnProperty.call(CAMPUSES, campusCode) ? CAMPUSES[campusCode] : null;
    if (campus) {
        warnings.push('节次时间按你选择的「' + campus.label +
            '」作息写入（教务处公布的 10 节制），选错校区会与实际上课时间不符，请在学期管理里核对。');
    } else {
        warnings.push('没有选择校区：唐槐 / 龙潭两校区作息不同，本次未写入节次时间，' +
            '课表会使用空课默认的节次时间，请在学期管理里核对。');
    }

    if (droppedWeeks) {
        warnings.push('有 ' + droppedWeeks + ' 个周次超出 1..30 的范围，已忽略（教务数据里的异常值）。');
    }

    if (skipped) {
        warnings.push('有 ' + skipped + ' 行课表数据缺少课名 / 星期 / 节次 / 周次，已跳过' +
            '（可能是教务系统的调课、待定之类记录）。');
    }

    if (maxWeek < MIN_TOTAL_WEEKS) {
        warnings.push('课表里最晚只排到第 ' + maxWeek + ' 周，学期总周数按 ' + MIN_TOTAL_WEEKS +
            ' 周写入，可在学期管理里调整。');
    }

    return JSON.stringify({
        specVersion: 1,
        kind: 'schedule',
        ocrAssisted: false,
        warnings: warnings,
        terms: [
            {
                name: termNameOf(info),
                firstDay: firstDayOf(info),
                totalWeeks: totalWeeks,
                periodTimes: campus ? campus.periodTimes : [],
                courses: courses
            }
        ]
    });
})()
