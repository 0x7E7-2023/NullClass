(function () {
    // 东北农业大学 教务适配器（URP 综合教务系统）—— 第二步：原始数据 → 空课课表载荷
    //
    // 移植自 shiguang_warehouse 的 NEAU/NEAU_01.js
    //   https://github.com/XingHeYuZhuan/shiguang_warehouse  （MIT，上游作者 dezige131）
    //
    // 输入是 extract.js 交出来的原始数据（__ncInput，JSON 字符串）：
    //   { fetchedDate: "2026-09-09", pageUrl: "…", raw: <教务接口原样 JSON> }
    // 其中 raw.xkxx 是「课程 ID → 课程对象」的字典（数组，取用其中每个对象），
    // 课程对象里的 timeAndPlaceList 是这门课每次上课的星期/节次/周次/地点；
    // raw.jcsjbs 是作息时间（jc 节次号 + kssj/jssj "0810" 这种 HHmm）。
    //
    // 移植改动（相对上游脚本）：
    //   ① 周次：上游把整串的单双周标志当成全局的（"1-8周(单),10-16周" 会把后半段一起过滤掉），
    //      这里按逗号分段、每段自判单双周；上游只读 weekDescription，这里在它缺失时
    //      退回 classWeek 位串（同为 URP 平台的其他学校用的是位串写法）。
    //   ② 教师/教室：上游兜底成「未知」「待定」，空课里教师允许为空 —— 空着比写「未知」好
    //      （「未知」会当成真名显示）。教室为空时同样留空。
    //   ③ 学期名与开学日：URP 这个接口只按「当前学期」返回，既没有学期号也没有校历。
    //      上游没有学期概念、也不管开学日；空课两者都必填，这里按抓取日期推算，
    //      并在 warnings 里如实说明（推算值在库里和真值长得一模一样，不说用户没机会发现）。
    //   ④ 总周数：教务不给，按课表里出现的最大周次推定（同样写进 warnings）。
    //   ⑤ 越界保护：规范要求 totalWeeks ≤ 30，教务偶尔出现更长的周次（或位串按 40 周排），
    //      这里截断到 30 周并写进 warnings，而不是让整份载荷校验失败。
    //   ⑥ 只读课表：接口里的课程名/教师/星期/节次/周次/教室之外的东西一概不取。
    //
    // 本文件是纯函数：同样的 __ncInput 永远得到同样的输出（CI 用 Rhino 跑它对 fixture）。
    var data = JSON.parse(__ncInput);
    var raw = (data && data.raw) ? data.raw : {};

    var MAX_TERM_WEEKS = 30;      // 规范：totalWeeks 必须落在 1..30
    var DEFAULT_TOTAL_WEEKS = 20;
    var NAME_LIST_LIMIT = 3;      // warnings 里最多点名几门课（单条提示上限 200 字）

    var warnings = [];
    var byCourse = {};
    var order = [];
    var noSlotNames = [];         // 教务没排上课时间地点（未排课 / 无课表课程）
    var brokenNames = [];         // 有安排，但星期/节次/周次读不出来
    var brokenPlaces = 0;
    var droppedBlocks = 0;        // 周次越界、整条被丢掉的安排
    var clippedBlocks = 0;        // 周次越界、被截断的安排
    var droppedCourses = [];      // 截断后一条安排都不剩的课

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

    function isArray(value) {
        return Object.prototype.toString.call(value) === '[object Array]';
    }

    function parseIsoDate(value) {
        var match = /^(\d{4})-(\d{1,2})-(\d{1,2})/.exec(text(value));
        if (!match) return null;
        var year = parseInt(match[1], 10);
        var month = parseInt(match[2], 10);
        var day = parseInt(match[3], 10);
        var date = new Date(year, month - 1, day);
        if (isNaN(date.getTime())) return null;
        return date;
    }

    // 第 1 周从「开学日所在周的周一」起算（空课的 firstDay 就是这么用的）
    function mondayOf(date) {
        var offset = (date.getDay() + 6) % 7;   // 周一=0 … 周日=6
        return new Date(date.getFullYear(), date.getMonth(), date.getDate() - offset);
    }

    function isoOf(date) {
        return date.getFullYear() + '-' + pad2(date.getMonth() + 1) + '-' + pad2(date.getDate());
    }

    // 接口只按「当前学期」返回，没有学年学期：按抓取日期落在哪一学期命名
    // （2–7 月算第二学期，8 月–次年 1 月算第一学期）。是推算值，写进 warnings。
    function inferTermName(date) {
        var year = date.getFullYear();
        var month = date.getMonth() + 1;
        if (month >= 2 && month <= 7) return (year - 1) + '-' + year + '学年第二学期';
        return year + '-' + (year + 1) + '学年第一学期';
    }

    function weeksFromBits(bits) {
        var weeks = [];
        for (var i = 0; i < bits.length; i++) {
            if (bits.charAt(i) === '1') weeks.push(i + 1);
        }
        return weeks;
    }

    // "1-16周" / "1,3,5周" / "1-16周(双)" / "2-6周,10周" / "第1-16周"：
    // 按逗号（含全角）分段，单双周标志只在它自己那一段里生效。
    function weeksFromText(source) {
        var weeks = [];
        var segments = text(source).split(/[,，;；]/);
        for (var i = 0; i < segments.length; i++) {
            var segment = segments[i];
            var onlyOdd = segment.indexOf('单') >= 0;
            var onlyEven = segment.indexOf('双') >= 0;
            var range = /(\d{1,2})\s*[-—~至]\s*(\d{1,2})/.exec(segment);
            if (range) {
                var start = parseInt(range[1], 10);
                var end = parseInt(range[2], 10);
                for (var w = start; w <= end; w++) {
                    if (onlyOdd && w % 2 === 0) continue;
                    if (onlyEven && w % 2 === 1) continue;
                    weeks.push(w);
                }
            } else {
                var single = /(\d{1,2})/.exec(segment);
                if (!single) continue;
                var one = parseInt(single[1], 10);
                if (onlyOdd && one % 2 === 0) continue;
                if (onlyEven && one % 2 === 1) continue;
                weeks.push(one);
            }
        }
        return weeks;
    }

    function uniqueSorted(weeks) {
        var seen = {};
        var out = [];
        for (var i = 0; i < weeks.length; i++) {
            var w = weeks[i];
            if (w >= 1 && !seen[w]) {
                seen[w] = true;
                out.push(w);
            }
        }
        out.sort(function (a, b) { return a - b; });
        return out;
    }

    // 周次集合 → 极大段：步长 1 视作每周，步长 2 视作单/双周
    function runsOf(weeks) {
        var runs = [];
        var i = 0;
        while (i < weeks.length) {
            var step = 1;
            if (i + 1 < weeks.length && weeks[i + 1] - weeks[i] === 2) step = 2;
            var j = i;
            while (j + 1 < weeks.length && weeks[j + 1] - weeks[j] === step) j++;
            var run = { start: weeks[i], end: weeks[j], weekType: 'ALL' };
            if (run.start !== run.end && step === 2) {
                run.weekType = run.start % 2 === 1 ? 'ODD' : 'EVEN';
            }
            runs.push(run);
            i = j + 1;
        }
        return runs;
    }

    function weeksOf(place) {
        var desc = text(place.weekDescription);
        // 有些 URP 部署把位串写在 weekDescription 里（"111111111100000"）
        if (/^[01]{8,}$/.test(desc)) return uniqueSorted(weeksFromBits(desc));
        var fromDesc = uniqueSorted(weeksFromText(desc));
        if (fromDesc.length) return fromDesc;
        var bits = text(place.classWeek);
        if (/^[01]{4,}$/.test(bits)) return uniqueSorted(weeksFromBits(bits));
        return [];
    }

    // "0810" → "08:10"；"080000" → "08:00"；已经是 "08:10" 就原样返回
    function formatClock(value) {
        var s = text(value);
        if (!s || s.indexOf(':') >= 0) return s;
        if (/^\d{4}$/.test(s) || /^\d{6}$/.test(s)) return s.substring(0, 2) + ':' + s.substring(2, 4);
        return s;
    }

    function minutesOf(clock) {
        var match = /^(\d{1,2}):(\d{2})$/.exec(clock);
        if (!match) return -1;
        return parseInt(match[1], 10) * 60 + parseInt(match[2], 10);
    }

    function buildPeriodTimes(slots) {
        var times = [];
        var broken = 0;
        if (!isArray(slots)) return { times: times, broken: 0 };
        for (var i = 0; i < slots.length; i++) {
            var slot = slots[i] || {};
            var index = intOf(slot.jc);
            var start = formatClock(slot.kssj);
            var end = formatClock(slot.jssj);
            var okStart = /^([01]?\d|2[0-3]):([0-5]\d)$/.test(start);
            var okEnd = /^([01]?\d|2[0-3]):([0-5]\d)$/.test(end);
            if (!(index >= 1) || !okStart || !okEnd || minutesOf(start) >= minutesOf(end)) {
                broken++;
                continue;
            }
            times.push({ periodIndex: index, start: start, end: end });
        }
        times.sort(function (a, b) { return a.periodIndex - b.periodIndex; });
        return { times: times, broken: broken };
    }

    function nameList(names) {
        var shown = names.slice(0, NAME_LIST_LIMIT).join('、');
        if (names.length > NAME_LIST_LIMIT) return shown + ' 等 ' + names.length + ' 门';
        return shown;
    }

    function pushWarning(message) {
        if (warnings.length < 20) warnings.push(message);
    }

    var apiError = text(raw.errorMessage);
    if (apiError) throw new Error('教务系统返回：' + apiError);

    var maps = [];
    if (isArray(raw.xkxx)) {
        for (var mi = 0; mi < raw.xkxx.length; mi++) {
            if (raw.xkxx[mi] && typeof raw.xkxx[mi] === 'object') maps.push(raw.xkxx[mi]);
        }
    } else if (raw.xkxx && typeof raw.xkxx === 'object') {
        maps.push(raw.xkxx);
    }
    if (!maps.length) {
        throw new Error('教务系统返回的数据里没有课表（缺少 xkxx）：请确认当前学期已经排课，' +
            '且页面停在教务系统学生端的课表页');
    }

    for (var m = 0; m < maps.length; m++) {
        var map = maps[m];
        var keys = Object.keys(map);
        for (var k = 0; k < keys.length; k++) {
            var info = map[keys[k]];
            if (!info || typeof info !== 'object') continue;

            var name = text(info.courseName);
            if (!name) continue;

            var places = info.timeAndPlaceList;
            if (!isArray(places) || !places.length) {
                // 教务里没排上课时间地点的课（未排课 / 无课表课程）——课表上放不下，如实报出
                if (noSlotNames.indexOf(name) < 0) noSlotNames.push(name);
                continue;
            }

            var teacher = text(info.attendClassTeacher).replace(/\*/g, '').trim();
            // 同名同教师合成一门课；键的分隔符用 NUL 的转义写法（源码里不塞不可见字节），
            // 课名与教师名里都不会出现这个字符
            var key = name + '\u0000' + teacher;
            var blocks = [];
            var seen = {};

            for (var p = 0; p < places.length; p++) {
                var place = places[p] || {};
                var weeks = weeksOf(place);
                var day = intOf(place.classDay);
                var startPeriod = intOf(place.classSessions);
                var length = intOf(place.continuingSession);
                if (!(length >= 1)) length = 1;   // 接口偶尔不给连续节数，按单节收
                if (!weeks.length || !(day >= 1 && day <= 7) || !(startPeriod >= 1)) {
                    brokenPlaces++;
                    continue;
                }

                var endPeriod = startPeriod + length - 1;
                var location = text(place.campusName) + text(place.teachingBuildingName) +
                    text(place.classroomName);
                var runs = runsOf(weeks);
                for (var r = 0; r < runs.length; r++) {
                    var run = runs[r];
                    var signature = day + '|' + startPeriod + '|' + endPeriod + '|' + run.start + '|' +
                        run.end + '|' + run.weekType + '|' + location;
                    if (seen[signature]) continue;   // 同一门课接口偶尔给重复的排课行
                    seen[signature] = true;
                    blocks.push({
                        dayOfWeek: day,
                        startPeriod: startPeriod,
                        endPeriod: endPeriod,
                        startWeek: run.start,
                        endWeek: run.end,
                        weekType: run.weekType,
                        location: location || null
                    });
                }
            }

            if (!blocks.length) {
                if (brokenNames.indexOf(name) < 0) brokenNames.push(name);
                continue;
            }
            if (!byCourse[key]) {
                byCourse[key] = { name: name, teacher: teacher || null, note: null, blocks: [] };
                order.push(key);
            }
            for (var b = 0; b < blocks.length; b++) byCourse[key].blocks.push(blocks[b]);
        }
    }

    if (!order.length) {
        if (brokenPlaces) {
            throw new Error('没有解析出任何课程：' + brokenPlaces +
                ' 条安排的星期/节次/周次读不出来，教务系统可能改了数据格式');
        }
        if (noSlotNames.length) {
            throw new Error('没有解析出任何课程：教务系统对 ' + noSlotNames.length +
                ' 门课都还没有排上课时间地点，等排课完成后再提取');
        }
        throw new Error('没有解析出任何课程：接口里没有这门学生的选课记录，' +
            '请确认页面停在教务系统学生端的课表页');
    }

    // 总周数：教务不给，按课表里出现的最大周次推定
    var maxWeek = 0;
    for (var c = 0; c < order.length; c++) {
        var courseBlocks = byCourse[order[c]].blocks;
        for (var i = 0; i < courseBlocks.length; i++) {
            if (courseBlocks[i].endWeek > maxWeek) maxWeek = courseBlocks[i].endWeek;
        }
    }
    var totalWeeks = maxWeek >= 1 ? maxWeek : DEFAULT_TOTAL_WEEKS;
    if (totalWeeks > MAX_TERM_WEEKS) totalWeeks = MAX_TERM_WEEKS;

    var courses = [];
    for (var ci = 0; ci < order.length; ci++) {
        var course = byCourse[order[ci]];
        var kept = [];
        for (var bi = 0; bi < course.blocks.length; bi++) {
            var block = course.blocks[bi];
            if (block.startWeek > totalWeeks) {
                droppedBlocks++;
                continue;
            }
            if (block.endWeek > totalWeeks) {
                block.endWeek = totalWeeks;
                clippedBlocks++;
            }
            kept.push(block);
        }
        if (!kept.length) {
            droppedCourses.push(course.name);
            continue;
        }
        course.blocks = kept;
        courses.push(course);
    }
    if (!courses.length) {
        throw new Error('课表里的安排周次都落在第 ' + totalWeeks + ' 周之后，无法放进一张课表');
    }

    var periodInfo = buildPeriodTimes(raw.jcsjbs);
    var fetched = parseIsoDate(data && data.fetchedDate);
    if (!fetched) fetched = new Date();   // extract.js 一定会带上抓取日期；手写输入时按当天算
    var firstDay = isoOf(mondayOf(fetched));

    pushWarning('开学日期（第 1 周开始日）教务没有提供：课表接口只返回本学期课表，不含校历。' +
        '已按抓取日 ' + isoOf(fetched) + ' 所在周的周一（' + firstDay + '）推算 —— ' +
        '它决定「现在第几周」，请到「学期管理」里核对成真实开学日');
    pushWarning('学期名是推算的：接口只按「当前学期」返回，没有学年学期字段。已按抓取日 ' +
        isoOf(fetched) + ' 推为「' + inferTermName(fetched) + '」，如与实际不符可在导入后重命名');
    pushWarning('学期总周数按课表里出现的最大周次（' + totalWeeks +
        ' 周）推定，接口没有给出总周数；校历周数不同的话可在「学期管理」里改');
    if (noSlotNames.length) {
        pushWarning('教务系统对 ' + noSlotNames.length + ' 门课还没有排上课时间地点（通常是还没排课），' +
            '已跳过：' + nameList(noSlotNames));
    }
    if (brokenNames.length || brokenPlaces) {
        var pieces = [];
        if (brokenNames.length) {
            pieces.push('有 ' + brokenNames.length + ' 门课一条安排都没解析出来，已跳过：' +
                nameList(brokenNames));
        }
        if (brokenPlaces) {
            pieces.push('另有 ' + brokenPlaces + ' 条安排的星期/节次/周次读不出来，已忽略');
        }
        pushWarning(pieces.join('；'));
    }
    if (clippedBlocks || droppedBlocks || droppedCourses.length) {
        var clipDetail = [];
        if (clippedBlocks) clipDetail.push(clippedBlocks + ' 条安排的周次被截断到第 ' + totalWeeks + ' 周');
        if (droppedBlocks) clipDetail.push(droppedBlocks + ' 条安排整条落在 ' + totalWeeks + ' 周之后已丢弃');
        if (droppedCourses.length) clipDetail.push('课程：' + nameList(droppedCourses));
        pushWarning('有安排的周次超出了空课支持的最大 ' + MAX_TERM_WEEKS + ' 周：' +
            clipDetail.join('，') + '，请核对教务里的周次写法');
    }
    if (periodInfo.broken) {
        pushWarning('教务返回的作息时间里有 ' + periodInfo.broken +
            ' 节的时间格式读不出来，已跳过该节；其余照常，请在导入后核对上课时间');
    }

    return JSON.stringify({
        specVersion: 1,
        kind: 'schedule',
        ocrAssisted: false,
        warnings: warnings,
        terms: [
            {
                name: inferTermName(fetched),
                firstDay: firstDay,
                totalWeeks: totalWeeks,
                periodTimes: periodInfo.times,
                courses: courses
            }
        ]
    });
})()
