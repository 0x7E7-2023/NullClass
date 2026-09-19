(function () {
    // 河南科技大学教务适配器（树维 EAMS 平台，/eams/）—— 第二步：纯转换。
    //
    // 移植自 shiguang_warehouse 的 HAUST/haust.js
    //   https://github.com/XingHeYuZhuan/shiguang_warehouse  （MIT，作者 Haooz）
    //   上游快照 e62554a4034386b893bcd6813c7b2b64f8c730a3（2026-09-12）
    // 上游平台名写的是「新版树维教务」；树维 = 上海树维信息科技有限公司（SupWisdom，新开普子公司），
    // 路径 /eams/ 与 dataQuery.action 正是这一族的口径（本批 12 件同平台）。**不是强智科技。**
    //
    // 输入是 extract.js 交出来的原始数据（见该文件头），本文件不碰 DOM、不发请求，只做转换。
    //
    // 移植改动（逐条对应上游函数）：
    //   ① 上游 parseTitle 按「固定三段括号」切：「^(.+?)\(([^)]+)\)\s*\(([^)]+)\)」 再把
    //      「(\d+[-~]\d+周」 之后的整段当明细。课程名里带括号（「大学物理（一）」）时第一段
    //      正则就会错位；一格两门课时也只解析第一门。这里改成**逐括号组向左游标**切
    //      （与同批 「masu」 同一路数）：每个「周次,节次[,教室]」括号组对应一门课，
    //      左侧依次是 课号 / 教师，课程名取游标到这一门课起点之间的文字。
    //   ② 上游把格子位置算成「列号就是星期几」（day = colIndex），且要求 day ∈ 1..7。
    //      树维课表首列是节次/星期标签列，列号与星期不等价。这里改成三条路，按可靠度排：
    //      ① 表头的「星期X」给出第几列是星期几；② 单元格 id「TD<行>_<星期>」里的下标；
    //      ③ 都没有就计数进 warnings（宁可报出来，不猜）。
    //   ③ 上游 parsePeriod 只认区间，「第3节」这种单个节次返回 null 而整门课丢掉。
    //      这里节次优先由**行位置**给出（表头「第N节」与格子所在行对应），格子自己的
    //      「3节」「1-2节」作为行位置读不到时的兜底 —— 两条路都读不出来就计数进 warnings。
    //   ④ 上游把「\s+」「,」都当分段符、且不认「单」「双」写在段内以外的位置。这里按
    //      段独立判单双：「单3-17」 / 「双2-8」 / 「1-3,5-9周」 / 「1-16周 双」 都能读；
    //      单双同时写、区间写反、认不出的段一律**计数 + 进 warnings**，不静默丢周次。
    //   ⑤ 上游 parseWeeks 产出显式周次数组；这里折算成极大段 + 单双类型（手册 §4.1）：
    //      连续段 ALL、标了单/双的段产出一个 ODD 或 EVEN 块（范围取周次的最小到最大）。
    //   ⑥ 上游位置/教室/教师为空时写死字符串；这里一律留 null（手册 §4.7：空着比写「未知」好）。
    //   ⑦ 作息时间：教务**没有**作息表接口，只能用课表表头自己的「第N节 HH:mm-HH:mm」。
    //      格子自己也带「第N-M节 HH:mm-HH:mm」时以格子为准（表头被打印成连堂、或表头那条
    //      时间不合法时由它顶上）。所有时间过 HH:mm 与 00:00–23:59 校验，越界/起止颠倒的
    //      一律丢掉并计数进 warnings —— 写错的时间不是「跳过一门课」，是**整个载荷被拒**。
    //      两条路都给不出时间时：表头连节次数都没给出就**留空 periodTimes**（宿主的默认
    //      作息表顶上来）；表头给出了节次数只是没给时间，就按每节 45 分钟补一组占位时间
    //      （否则课表会有节次却没有任何时间）—— 两种情况都写进 warnings。
    //   ⑧ 开学日：上游让用户从学期列表里选（showSingleSelection）。这里自动取**当前学期**
    //      —— 按 today 落在[startDate, endDate]里的那一个；取不到就用最近一个已经开始的学期；
    //      学期列表整个取不到才按「最近的周一」推算。开学日取开学日期所在那一周的周一
    //      （手册 §4.3）。**取自哪里一律写进 warnings**，推算值不许默默混进去。
    //   ⑨ 学期名用教务给的「<学年>学年第<学期>学期」；拿不到就用「河南科技大学 +
    //      按导入日期推算的学年学期」，并写进 warnings。
    //   ⑩ 同一门课在多个格子里重复出现（合班、连堂拆格）时按整条安排去重（上游没有这一步，
    //      按「课名+星期+起始节+周次」去重，教师/教室不同就会被当成同一门课）。
    // 本文件不碰 DOM、不发请求、不读全局，输入只有 __ncInput —— CI 里用 Rhino 实跑回归。
    var data = JSON.parse(__ncInput);

    var MAX_TOTAL_WEEKS = 30;
    var DEFAULT_TOTAL_WEEKS = 20;
    var MAX_PERIOD = 40;
    var MAX_WARNINGS = 20;
    var MAX_WARNING_TEXT = 200;
    var SCHOOL_NAME = '河南科技大学';
    var COURSE_CODE_RE = /^[0-9][0-9._-]*$/;
    var PERIOD_RE = /^([0-9]{1,2})[-~—－–]([0-9]{1,2})$/;

    var warnings = [];

    function warn(message) {
        var text = String(message);
        if (text.length > MAX_WARNING_TEXT) text = text.substring(0, MAX_WARNING_TEXT - 1) + '…';
        if (warnings.length >= MAX_WARNINGS) return;
        if (warnings.indexOf(text) >= 0) return;
        warnings.push(text);
    }

    function text(value) {
        if (value === null || value === undefined) return '';
        return String(value);
    }

    function tidy(value) {
        return text(value).replace(/\s+/g, ' ').trim();
    }

    function squeeze(value) {
        return text(value).replace(/\s+/g, '');
    }

    function intOf(value, fallback) {
        var n = parseInt(value, 10);
        return isNaN(n) ? fallback : n;
    }

    function isArray(value) {
        return Object.prototype.toString.call(value) === '[object Array]';
    }

    function pad2(n) {
        return (n < 10 ? '0' : '') + n;
    }

    function isoOf(date) {
        return date.getFullYear() + '-' + pad2(date.getMonth() + 1) + '-' + pad2(date.getDate());
    }

    function parseIso(value) {
        var m = /^([0-9]{4})-([0-9]{1,2})-([0-9]{1,2})$/.exec(tidy(value));
        if (!m) return null;
        return new Date(intOf(m[1], 1970), intOf(m[2], 1) - 1, intOf(m[3], 1));
    }

    function shiftDays(date, days) {
        return new Date(date.getFullYear(), date.getMonth(), date.getDate() + days);
    }

    // 开学日回退到那一周的周一（手册 §4.3；缺省每周起始日就是周一）
    function mondayIso(date) {
        return isoOf(shiftDays(date, -((date.getDay() + 6) % 7)));
    }

    function minOf(value) {
        var m = /^([0-9]{1,2}):([0-9]{2})$/.exec(text(value));
        if (!m) return -1;
        var h = intOf(m[1], -1);
        var mi = intOf(m[2], -1);
        if (h < 0 || h > 23 || mi < 0 || mi > 59) return -1;
        return h * 60 + mi;
    }

    // 时间必须是 HH:mm 且 00:00–23:59（越界不是跳过一条，是整个载荷被拒）
    function hhmmOf(value) {
        var m = /^([0-9]{1,2}):([0-9]{1,2})$/.exec(tidy(value));
        if (!m) return null;
        var h = intOf(m[1], -1);
        var mi = intOf(m[2], -1);
        if (h < 0 || h > 23 || mi < 0 || mi > 59) return null;
        return pad2(h) + ':' + pad2(mi);
    }

    // ---------------------------------------------------------------- 周次文本
    // 「1-16周」「单3-17」「双2-8」「1-3,5-9周」「1-16周 双」「1-3 5-9周」都要能读。
    // 返回 {weeks, odd, even, issues}：odd/even 是**整段周次**的单双标记（上游 parseWeeks 同义），
    // issues 是读不出来的段数 —— 调用方必须拿它出声。
    function weeksFromText(value) {
        var result = { weeks: [], odd: false, even: false, issues: 0 };
        var source = text(value).replace(/第/g, '').replace(/周/g, '');
        var parts = source.split(/[,，;；、]/);
        for (var i = 0; i < parts.length; i++) {
            var segments = parts[i].split(/\s+/);
            for (var j = 0; j < segments.length; j++) {
                readWeekSegment(segments[j], result);
            }
        }
        return result;
    }

    function readWeekSegment(segment, result) {
        var seg = text(segment).replace(/[()（）]/g, '');
        if (!seg) return;
        var odd = seg.indexOf('单') >= 0;
        var even = seg.indexOf('双') >= 0;
        if (odd && even) {
            result.issues++;
            return;
        }
        if (odd) result.odd = true;
        if (even) result.even = true;
        var clean = seg.replace(/[单双]/g, '').replace(/\s+/g, '');
        if (!clean) return;
        var range = PERIOD_RE.exec(clean);
        if (range) {
            var start = intOf(range[1], 0);
            var end = intOf(range[2], 0);
            if (start < 1 || end < start) {
                result.issues++;
                return;
            }
            for (var w = start; w <= end; w++) result.weeks.push(w);
            return;
        }
        if (/^[0-9]{1,2}$/.test(clean)) {
            var single = intOf(clean, 0);
            if (single >= 1) {
                result.weeks.push(single);
                return;
            }
        }
        result.issues++;
    }

    function uniqueSorted(values) {
        var seen = {};
        var out = [];
        for (var i = 0; i < values.length; i++) {
            var value = values[i];
            if (value >= 1 && !seen[value]) {
                seen[value] = true;
                out.push(value);
            }
        }
        out.sort(function (a, b) { return a - b; });
        return out;
    }

    // 周次集合 → 极大段：连续段 ALL、隔周段 ODD/EVEN（手册 §4.1）。
    // 单双标记作用在整段周次上（上游 parseWeeks 的 isOdd / isEven 就是这个意思）：
    // 标了单/双的段只产出**一个**块，范围取周次的最小到最大 —— 单周课本来就是每周隔一周上，
    // 展开成「W1-W3 等」会变成几十个块。
    function runsOf(spec) {
        var weeks = uniqueSorted(spec.weeks);
        if (!weeks.length) return [];
        if (spec.odd) return [{ start: weeks[0], end: weeks[weeks.length - 1], weekType: 'ODD' }];
        if (spec.even) return [{ start: weeks[0], end: weeks[weeks.length - 1], weekType: 'EVEN' }];
        var runs = [];
        var i = 0;
        while (i < weeks.length) {
            var j = i;
            while (j + 1 < weeks.length && weeks[j + 1] - weeks[j] === 1) j++;
            runs.push({ start: weeks[i], end: weeks[j], weekType: 'ALL' });
            i = j + 1;
        }
        return runs;
    }

    // ---------------------------------------------------------------- 标题文本
    function stripHtml(value) {
        return text(value).replace(/<[^>]*>/g, '');
    }

    // 扫出文本里所有半角括号组（课程名里的全角括号不是格式的一部分，留在名字里）
    function parenGroups(source) {
        var out = [];
        var i = 0;
        while (i < source.length) {
            if (source.charAt(i) !== '(') {
                i++;
                continue;
            }
            var depth = 0;
            var j = i;
            for (; j < source.length; j++) {
                var ch = source.charAt(j);
                if (ch === '(') depth++;
                else if (ch === ')') {
                    depth--;
                    if (depth === 0) break;
                }
            }
            if (depth !== 0) break;
            out.push({ start: i, end: j + 1, text: source.substring(i + 1, j) });
            i = j + 1;
        }
        return out;
    }

    function periodIn(value) {
        var clean = squeeze(value).replace(/[节次课]/g, '');
        var m = PERIOD_RE.exec(clean);
        if (m) {
            var start = intOf(m[1], 0);
            var end = intOf(m[2], 0);
            if (start < 1 || start > MAX_PERIOD || end < start || end > MAX_PERIOD) return null;
            return { start: start, end: end };
        }
        // 单个节次也认：「3节」= 第 3 节（上游 parsePeriod 只认区间，遇到「3节」会 null 掉整门课）
        var single = /^([0-9]{1,2})$/.exec(clean);
        if (single) {
            var only = intOf(single[1], 0);
            if (only >= 1 && only <= MAX_PERIOD) return { start: only, end: only };
        }
        return null;
    }

    // 一段文字像不像周次（「1-16周」「单3-17」「1-3,5-9周」）。只允许周次该有的字符且含数字 ——
    // 否则「(张伟,李娜)」「202520261.11202001.002」这种会被当成周次。
    var WEEK_CHARS_RE = /^[\s0-9单双周第,，、\-~—－–]*$/;

    function looksLikeWeeks(value) {
        var s = tidy(value);
        if (!/[0-9]/.test(s)) return false;
        return WEEK_CHARS_RE.test(s);
    }

    // 一个括号组是不是「周次,节次（,教室）」。上游按固定下标切（parts[0]/parts[1]/parts.slice(2)），
    // 周次里带逗号（「1-3,5-9周」）时会把教室吃进周次、也会把整门课丢掉。
    // 这里先找「节」字所在的段（第 0 段之后的第一段），它左边是周次、右边是教室；
    // 没有「节」字就退回「含周字」的写法，周次只取第 0 段，其余当教室。
    function detailOf(inner) {
        var s = tidy(inner);
        if (!s) return null;
        var parts = s.split(',');
        var at = -1;
        for (var i = 1; i < parts.length; i++) {
            if (parts[i].indexOf('节') >= 0) {
                at = i;
                break;
            }
        }
        if (at >= 1) {
            var head = parts.slice(0, at).join(',');
            var spelled = periodIn(parts[at]);
            if (spelled && looksLikeWeeks(head)) {
                return { weeks: head, period: spelled, room: parts.slice(at + 1).join(',') };
            }
        }
        if (s.indexOf('周') >= 0 && looksLikeWeeks(parts[0])) {
            return { weeks: parts[0], period: null, room: parts.slice(1).join(',') };
        }
        return null;
    }

    // 标题里出现的所有「第N-M节 HH:mm-HH:mm」组（可能写在课号/教师那一段里）。
    // 这种组既不是课号也不是教师，必须先从括号组序列里挑出来，否则会被当成教师。
    var TIME_GROUP_RE = /^第?\s*([0-9]{1,2}(?:\s*[-~—－–]\s*[0-9]{1,2})?)\s*节\s*([0-9]{1,2}:[0-9]{2})\s*[-~—－–]\s*([0-9]{1,2}:[0-9]{2})$/;

    function isTimeGroup(value) {
        var t = squeeze(value).replace(/^\(+/, '').replace(/\)+$/, '');
        return TIME_GROUP_RE.test(t);
    }

    // 同一格里出现的所有「第N节」时间组：同一段起止时间被写成两个连堂格子时并成一条
    function timeGroupsOf(source) {
        var s = text(source);
        var re = /\(\s*第?\s*([0-9]{1,2}(?:\s*[-~—－–]\s*[0-9]{1,2})?)\s*节\s*([0-9]{1,2}:[0-9]{2})\s*[-~—－–]\s*([0-9]{1,2}:[0-9]{2})\s*\)/g;
        var out = [];
        var m;
        while ((m = re.exec(s)) !== null) {
            var tokens = [];
            var indexes = squeeze(m[1]).split(/[-~—－–]/);
            for (var i = 0; i < indexes.length; i++) {
                var index = intOf(indexes[i], 0);
                if (index >= 1 && index <= MAX_PERIOD) tokens.push(index);
            }
            if (!tokens.length) continue;
            out.push({ tokens: tokens, start: m[2], end: m[3] });
        }
        var merged = [];
        for (var k = 0; k < out.length; k++) {
            var last = merged.length ? merged[merged.length - 1] : null;
            if (last && last.start === out[k].start && last.end === out[k].end) {
                for (var t = 0; t < out[k].tokens.length; t++) {
                    if (last.tokens.indexOf(out[k].tokens[t]) < 0) last.tokens.push(out[k].tokens[t]);
                }
                continue;
            }
            merged.push({ tokens: out[k].tokens.slice(0), start: out[k].start, end: out[k].end });
        }
        return merged;
    }

    // 一个格子里可能串着多门课（「课程A(...)(1-2周,…)课程B(...)(3-4周,…)」）。
    // 每个「周次,节次,教室」括号组认一门课，向左取最近的两个括号组当 课号 / 教师
    // （课号是纯数字点号那种，教师是剩下的那个）。
    function parseTitle(raw) {
        var s = tidy(stripHtml(text(raw)).replace(/;;;/g, ' '));
        if (!s) return [];
        var groups = parenGroups(s);
        if (!groups.length) return [];
        var out = [];
        var cursor = 0;
        var pending = [];
        var beforeDetail = false;
        for (var i = 0; i < groups.length; i++) {
            var group = groups[i];
            var detail = detailOf(group.text);
            if (!detail) {
                pending.push(group);
                continue;
            }
            // 括号组认出来了就是一门课的边界；周次读不出来照样产出这门课，
            // 由调用方计数并进 warnings（静默丢课是最不能接受的错）
            var weeks = weeksFromText(detail.weeks);
            var last = pending.length ? pending[pending.length - 1] : null;
            var before = pending.length > 1 ? pending[pending.length - 2] : null;
            // 「第5-6节 15:00-16:35」这种组既不是课号也不是教师，先摘掉
            if (last && isTimeGroup(last.text)) {
                pending.pop();
                last = pending.length ? pending[pending.length - 1] : null;
                before = pending.length > 1 ? pending[pending.length - 2] : null;
            }
            var code = null;
            var teacher = null;
            if (last && isTimeGroup(last.text)) {
                teacher = null;
                code = before;
            } else if (last && COURSE_CODE_RE.test(tidy(last.text))) {
                code = last;
                teacher = before;
            } else {
                teacher = last;
                code = before;
            }
            if (code && isTimeGroup(code.text)) code = null;
            if (teacher && isTimeGroup(teacher.text)) teacher = null;
            var nameEnd = code ? code.start : (teacher ? teacher.start : group.start);
            var name = tidy(s.substring(cursor, nameEnd));
            cursor = group.end;
            pending = [];
            // 名字末尾残留的课程序号（「大学英语(1)」这种）不是名字的一部分
            name = name.replace(/\([0-9]{1,3}\)$/, '').replace(/（[0-9]{1,3}）$/, '').trim();
            if (!name) continue;
            var tokens = [];
            for (var ps = detail.period ? detail.period.start : 0; ps > 0 && ps <= (detail.period ? detail.period.end : 0); ps++) tokens.push(ps);
            out.push({
                name: name,
                teacher: tidy(teacher ? teacher.text : ''),
                location: tidy(detail.room),
                weeks: weeks.weeks,
                odd: weeks.odd,
                even: weeks.even,
                issues: weeks.issues,
                startPeriod: detail.period ? detail.period.start : 0,
                endPeriod: detail.period ? detail.period.end : 0,
                tokens: tokens,
                time: null
            });
            beforeDetail = true;
        }
        // 这一格自己写的时间：并给同一节的课（连堂的两格写法用得上）
        var timeGroups = timeGroupsOf(s);
        for (var gi = 0; gi < out.length; gi++) {
            var entry = out[gi];
            if (entry.time) continue;
            for (var tg = 0; tg < timeGroups.length; tg++) {
                var candidate = timeGroups[tg];
                var hit = false;
                for (var ti = 0; ti < entry.tokens.length; ti++) {
                    if (candidate.tokens.indexOf(entry.tokens[ti]) >= 0) hit = true;
                }
                if (!hit && out.length === 1 && timeGroups.length === 1 && !entry.tokens.length) hit = true;
                if (!hit) continue;
                entry.time = candidate;
                break;
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- 课表格子
    var DAY_NAMES = { '一': 1, '二': 2, '三': 3, '四': 4, '五': 5, '六': 6, '日': 7, '天': 7 };
    var DAY_RE = /星期([一二三四五六日天])/;
    var ID_RE = /^TD([0-9]+)_([0-9]+)$/;

    function dayColumnFromHints(hints) {
        if (!isArray(hints)) return -1;
        for (var i = 0; i < hints.length; i++) {
            var hint = hints[i] || {};
            var m = DAY_RE.exec(tidy(hint.text));
            if (!m) continue;
            var day = DAY_NAMES[m[1]];
            if (day === 1) return intOf(hint.col, -1);
        }
        return -1;
    }

    function rowPeriods(hints) {
        var map = {};
        if (!isArray(hints)) return map;
        for (var i = 0; i < hints.length; i++) {
            var hint = hints[i] || {};
            var index = intOf(hint.index, 0);
            var row = intOf(hint.row, -1);
            if (index < 1 || index > MAX_PERIOD || row < 0) continue;
            if (map[row] === undefined) map[row] = index;
        }
        return map;
    }

    var dayColumn = dayColumnFromHints(data.days);
    var periodsByRow = rowPeriods(data.periods);

    function dayOfCell(cell, span) {
        if (dayColumn >= 0) {
            var day = intOf(cell.col, -1) - dayColumn + 1;
            return day >= 1 && day <= 7 ? day : 0;
        }
        // 单元格 id「TD<行>_<星期>」：上游的 day = colIndex 就是这个意思 ——
        // 第二段数字本身就是星期几（课表首列是标签列，所以星期一那格是 1）。
        var m = ID_RE.exec(text(cell.id));
        if (m) {
            var fromId = intOf(m[2], -1);
            return fromId >= 1 && fromId <= 7 ? fromId : 0;
        }
        return 0;
    }

    function periodRangeOfCell(cell, parsed, span) {
        var start = periodsByRow[intOf(cell.row, -1)];
        if (!(start >= 1)) start = parsed.startPeriod;
        if (!(start >= 1)) return null;
        var end = start + (span > 1 ? span - 1 : 0);
        if (parsed.endPeriod > end) end = parsed.endPeriod;
        if (end > MAX_PERIOD) end = MAX_PERIOD;
        return { start: start, end: end };
    }

    // ---------------------------------------------------------------- 学期与开学日
    function semesterLabel(semester) {
        var year = tidy(semester.schoolYear);
        var term = tidy(semester.name);
        if (!year && !term) return '';
        if (!year) return term.indexOf('学期') >= 0 ? term : term + '学期';
        if (term.indexOf('学期') >= 0) return year + '学年' + term;
        return year + '学年第' + term + '学期';
    }

    function semesterSpan(semester) {
        var start = parseIso(semester.startDate);
        var end = parseIso(semester.endDate);
        if (!start) return null;
        return { start: start, end: end };
    }

    // 自动取当前学期：today 落在起止日期里的那一个；没有就用「最近一个已经开始的学期」；
    // 连起止日期都没有就用列表里的第 0 条（extract 交出来的顺序就是教务给的顺序）。
    function pickSemester(list, today) {
        var best = null;
        var bestSpan = null;
        var i;
        for (i = 0; i < list.length; i++) {
            var span = semesterSpan(list[i]);
            if (!span) continue;
            if (today.getTime() >= span.start.getTime() &&
                (!span.end || today.getTime() <= span.end.getTime())) {
                return list[i];
            }
            if (span.start.getTime() <= today.getTime()) {
                if (!bestSpan || span.start.getTime() > bestSpan.start.getTime()) {
                    best = list[i];
                    bestSpan = span;
                }
            }
        }
        if (best) return best;
        for (i = 0; i < list.length; i++) {
            if (!bestSpan) {
                best = list[i];
                bestSpan = semesterSpan(list[i]);
                continue;
            }
            var candidate = semesterSpan(list[i]);
            if (candidate && candidate.start.getTime() < bestSpan.start.getTime()) {
                best = list[i];
                bestSpan = candidate;
            }
        }
        return best;
    }

    // 学期名教务拿不到时按导入日期推：9 月到次年 1 月算第一学期，2-8 月算第二学期
    function academicTermName(now) {
        var year = now.getFullYear();
        var month = now.getMonth() + 1;
        if (month >= 9) return year + '-' + (year + 1) + '学年第一学期';
        if (month === 1) return (year - 1) + '-' + year + '学年第一学期';
        return (year - 1) + '-' + year + '学年第二学期';
    }

    // ---------------------------------------------------------------- 作息时间
    function periodTimesFromHints(hints) {
        var out = [];
        var seen = {};
        var missing = 0;
        var dropped = 0;
        if (!isArray(hints)) return { list: out, missing: missing, dropped: dropped, total: 0 };
        for (var i = 0; i < hints.length; i++) {
            var hint = hints[i] || {};
            var index = intOf(hint.index, 0);
            if (!(index >= 1 && index <= MAX_PERIOD) || seen[index]) continue;
            if (!tidy(hint.start) || !tidy(hint.end)) {
                // 表头只写了「第N节」没写时间：后面若有同号的格子带了时间还能补上
                missing++;
                continue;
            }
            var start = hhmmOf(hint.start);
            var end = hhmmOf(hint.end);
            if (start === null || end === null || minOf(start) >= minOf(end)) {
                dropped++;
                continue;
            }
            seen[index] = true;
            out.push({ periodIndex: index, start: start, end: end });
        }
        out.sort(function (a, b) { return a.periodIndex - b.periodIndex; });
        return { list: out, missing: missing, dropped: dropped, total: isArray(hints) ? hints.length : 0 };
    }

    // ---------------------------------------------------------------- 组装
    var today = parseIso(data.today) || new Date();
    var semesters = isArray(data.semesters) ? data.semesters : [];
    var semester = semesters.length ? pickSemester(semesters, today) : null;
    var usedFallbackTerm = semester ? !semesterSpan(semester) : false;
    var firstDaySource = semester && !usedFallbackTerm
        ? '教务给的学期起止日期（' + tidy(semester.startDate) + ' 至 ' +
            (tidy(semester.endDate) || '教务没给') + '）'
        : '';

    var cells = isArray(data.cells) ? data.cells : [];
    var ordered = [];
    for (var c = 0; c < cells.length; c++) ordered.push(cells[c]);
    ordered.sort(function (a, b) {
        return (intOf(a.row, -1) - intOf(b.row, -1)) || (intOf(a.col, -1) - intOf(b.col, -1));
    });

    if (!ordered.length) {
        throw new Error('这一页里没找到课表（既没有 td[title]，也没有 td[id^=TD] 的课表格子）。' +
            '请先登录教务系统并打开「我的课表」，确保课表已经完整显示，再点「提取课表」');
    }

    var courses = [];
    var byCourse = {};
    var unparsedCells = 0;
    var noWeekCourses = 0;
    var noDayCells = 0;
    var noPeriodCells = 0;
    var weekIssues = 0;
    var periodTimeOverrides = {};

    for (var i = 0; i < ordered.length; i++) {
        var cell = ordered[i];
        var raw = tidy(cell.title);
        if (!raw) raw = tidy(cell.text);
        if (!raw) continue;
        var parsed = parseTitle(raw);
        if (!parsed.length) {
            // 「备注」这类页面杂物不算解析失败；带括号或带 title 的格子才像课程格
            if (raw.indexOf('(') >= 0 || tidy(cell.title).length > 0) unparsedCells++;
            continue;
        }
        var span = intOf(cell.span, 1);
        if (!(span >= 1)) span = 1;
        var day = dayOfCell(cell, span);
        if (!day) {
            noDayCells++;
            continue;
        }
        for (var p = 0; p < parsed.length; p++) {
            var course = parsed[p];
            if (course.issues) weekIssues += course.issues;
            var position = periodRangeOfCell(cell, course, span);
            var runs = runsOf(course);
            if (!runs.length) {
                noWeekCourses++;
                continue;
            }
            if (!position) {
                noPeriodCells++;
                continue;
            }
            // 这一格自己带了节次时间（「第1-2节 08:00-08:45」）时以格子为准，表头那份作废 ——
            // 表头被打印成连堂（一个标签盖两行、只给一个时间）的情况就是这么修的。
            if (course.time) {
                var timeStart = hhmmOf(course.time.start);
                var timeEnd = hhmmOf(course.time.end);
                if (timeStart !== null && timeEnd !== null && minOf(timeStart) < minOf(timeEnd)) {
                    for (var t = 0; t < course.time.tokens.length; t++) {
                        periodTimeOverrides[course.time.tokens[t]] = { start: timeStart, end: timeEnd };
                    }
                }
            }
            var key = course.name + '|' + course.teacher;
            if (!byCourse[key]) {
                byCourse[key] = { name: course.name, teacher: course.teacher || null, note: null, blocks: [] };
                courses.push(byCourse[key]);
            }
            for (var r = 0; r < runs.length; r++) {
                byCourse[key].blocks.push({
                    dayOfWeek: day,
                    startPeriod: position.start,
                    endPeriod: position.end,
                    startWeek: runs[r].start,
                    endWeek: runs[r].end,
                    weekType: runs[r].weekType,
                    location: course.location || null
                });
            }
        }
    }

    if (!courses.length) {
        throw new Error('课表页面上没有解析到任何课程，请确认「我的课表」里已经有排课、课表已完整显示，然后再点「提取课表」');
    }

    // 同一门课在多个格子里重复出现（合班、连堂拆格）时去掉重复的块
    var maxWeek = 0;
    for (var ci = 0; ci < courses.length; ci++) {
        var blocks = courses[ci].blocks;
        blocks.sort(function (a, b) {
            return (a.dayOfWeek - b.dayOfWeek) || (a.startPeriod - b.startPeriod) ||
                (a.startWeek - b.startWeek) || (a.endWeek - b.endWeek) ||
                (a.weekType < b.weekType ? -1 : (a.weekType > b.weekType ? 1 : 0));
        });
        var unique = [];
        var seenBlocks = {};
        for (var bi = 0; bi < blocks.length; bi++) {
            var block = blocks[bi];
            var blockKey = block.dayOfWeek + ':' + block.startPeriod + ':' + block.endPeriod + ':' +
                block.startWeek + ':' + block.endWeek + ':' + block.weekType + ':' + text(block.location);
            if (seenBlocks[blockKey]) continue;
            seenBlocks[blockKey] = true;
            unique.push(block);
            if (block.endWeek > maxWeek) maxWeek = block.endWeek;
        }
        courses[ci].blocks = unique;
    }

    var totalWeeks = maxWeek > DEFAULT_TOTAL_WEEKS ? maxWeek : DEFAULT_TOTAL_WEEKS;
    if (totalWeeks > MAX_TOTAL_WEEKS) totalWeeks = MAX_TOTAL_WEEKS;
    var clampedWeeks = false;
    if (maxWeek > totalWeeks) {
        clampedWeeks = true;
        for (ci = 0; ci < courses.length; ci++) {
            var list = courses[ci].blocks;
            for (var k = 0; k < list.length; k++) {
                if (list[k].endWeek > totalWeeks) list[k].endWeek = totalWeeks;
                if (list[k].startWeek > totalWeeks) list[k].startWeek = totalWeeks;
            }
        }
        maxWeek = totalWeeks;
    }

    // 作息时间：只有课表表头这一个来源（教务没有作息表接口）
    var hintList = isArray(data.periods) ? data.periods : [];
    var periodHint = periodTimesFromHints(hintList);
    var maxBlockPeriod = 0;
    for (var mi = 0; mi < courses.length; mi++) {
        for (var mj = 0; mj < courses[mi].blocks.length; mj++) {
            if (courses[mi].blocks[mj].endPeriod > maxBlockPeriod) maxBlockPeriod = courses[mi].blocks[mj].endPeriod;
        }
    }
    var li;
    for (li = 0; li < hintList.length; li++) {
        var extraIndex = intOf(hintList[li].index, 0);
        if (extraIndex > maxBlockPeriod) maxBlockPeriod = extraIndex;
    }
    // 这一格自己写的时间优先（表头被打印成连堂、或者表头那条时间不合法时，由它顶上）
    var periodTimeMap = {};
    for (li = 0; li < periodHint.list.length; li++) {
        var hinted = periodHint.list[li];
        periodTimeMap[hinted.periodIndex] = { start: hinted.start, end: hinted.end };
    }
    for (var overrideKey in periodTimeOverrides) {
        if (!Object.prototype.hasOwnProperty.call(periodTimeOverrides, overrideKey)) continue;
        var overrideIndex = intOf(overrideKey, 0);
        if (!(overrideIndex >= 1 && overrideIndex <= MAX_PERIOD)) continue;
        periodTimeMap[overrideIndex] = periodTimeOverrides[overrideKey];
    }
    var periodTimes = [];
    for (var periodNo = 1; periodNo <= maxBlockPeriod; periodNo++) {
        var settled = periodTimeMap[periodNo];
        if (!settled) continue;
        periodTimes.push({ periodIndex: periodNo, start: settled.start, end: settled.end });
    }
    if (!periodTimes.length && maxBlockPeriod >= 1 && hintList.length) {
        // 表头的时间一条都没用上（缺时间或格式不合法），但表头本身给出了节次数：
        // 按节次补占位条目，开头的第 1 节从 00:00 起、每节 45 分钟。
        // 宿主只在 periodTimes 整个为空时才顶默认作息表，这里若留空，课表会有节次但没有时间。
        for (var extra = 1; extra <= maxBlockPeriod; extra++) {
            var from = (extra - 1) * 45;
            periodTimes.push({
                periodIndex: extra,
                start: pad2(Math.floor(from / 60)) + ':' + pad2(from % 60),
                end: pad2(Math.floor((from + 45) / 60)) + ':' + pad2((from + 45) % 60)
            });
        }
        warn('课表表头里的节次时间都读不出来，已按每节 45 分钟补了一组占位时间（第 1 节从 00:00 起），请在节次设置里改成教务的实际作息');
    }
    if (!periodTimes.length) {
        var why = periodHint.total > 0
            ? '课表表头里的节次时间读不出来'
            : '课表页面上没有节次时间';
        warn('本学期没有读到作息时间（' + why + '），导入后会先用空课的默认作息表，请在节次设置里核对');
    } else {
        if (periodHint.dropped) {
            warn('课表表头里有 ' + periodHint.dropped + ' 条节次时间不合法（不是 HH:mm 或起止颠倒），已跳过，这几节会没有上下课时间');
        }
        if (periodHint.missing) {
            warn('课表表头里有 ' + periodHint.missing + ' 节只写了「第N节」没有上下课时间，这几节会没有时间');
        }
    }

    // 开学日
    var firstDayIso = '';
    var termName = '';
    if (semester) {
        termName = semesterLabel(semester);
        var span = semesterSpan(semester);
        if (span) firstDayIso = mondayIso(span.start);
    }
    if (!firstDayIso) {
        firstDayIso = mondayIso(today);
    }
    if (firstDaySource) {
        warn('开学日期取自' + firstDaySource + '，第 1 周按 ' + firstDayIso + '（周一）计，请在学期管理里核对');
    } else {
        warn('开学日期教务没有提供，已按最近的周一（' + firstDayIso + '）推算，请在学期管理里核对');
    }
    if (!termName) {
        termName = SCHOOL_NAME + ' ' + academicTermName(today);
        warn('学期名教务没有提供，已按导入日期推算为「' + termName + '」，如与实际不符可在学期管理里改名');
    }
    if (data.semesterError) {
        warn('学期列表没有取到（' + tidy(data.semesterError) + '），学期名与开学日已按导入日期推算');
    }
    if (maxWeek < DEFAULT_TOTAL_WEEKS) {
        warn('学期总周数教务没有提供，已按 ' + totalWeeks + ' 周计（课表里最大的周次是 ' + maxWeek + ' 周），如校历不同请在学期管理里调整');
    }
    if (clampedWeeks) {
        warn('课表里出现了超过 ' + MAX_TOTAL_WEEKS + ' 周的周次，已按 ' + totalWeeks + ' 周截断，请核对课表');
    }
    if (unparsedCells) {
        warn('有 ' + unparsedCells + ' 个课表格子的文字格式与预期不符（读不出「课程名(课号)(教师)(周次,节次,教室)」），已跳过，请核对课表');
    }
    if (noWeekCourses) {
        warn('有 ' + noWeekCourses + ' 门课程没能解析出周次，已跳过，请核对课表');
    }
    if (weekIssues) {
        warn('有 ' + weekIssues + ' 段周次写法读不出来（可能单双同写、区间写反），已跳过这些段，请核对课表');
    }
    if (noDayCells) {
        warn('有 ' + noDayCells + ' 个格子没能定位到星期（表头没有「星期X」行、单元格 id 也不是 TD<行>_<星期> 的写法），已跳过，请核对课表');
    }
    if (noPeriodCells) {
        warn('有 ' + noPeriodCells + ' 个格子没能定位到节次（表头没有「第N节」、格子自己的节次也读不出来），已跳过，请核对课表');
    }

    return JSON.stringify({
        specVersion: 1,
        kind: 'schedule',
        ocrAssisted: false,
        warnings: warnings,
        terms: [
            {
                name: termName,
                firstDay: firstDayIso,
                totalWeeks: totalWeeks,
                periodTimes: periodTimes,
                courses: courses
            }
        ]
    });
})()
