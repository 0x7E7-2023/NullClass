(function () {
    // 马鞍山学院教务系统（强智 eams 平台）适配器 —— 第二步：纯转换。
    //
    // 移植自 shiguang_warehouse 的 MASU/masu.js
    //   https://github.com/XingHeYuZhuan/shiguang_warehouse  （MIT，作者 Haooz）
    // 上游把这个脚本标成「青果/URP 金刚教务」，但学校教务处公布的地址是 /eams/login.action，
    // 解析的又是 #manualArrangeCourseTable + 行内「第N节」标签 —— 那是强智 eams 的结构
    // （同一批上游适配器里的 HPU / HIIT 也是这套）。课表是一张真正的 <table>，不是 canvas /
    // 图片，所以不需要走 OCR。这条只影响说明文字，不影响本文件的算法。
    //
    // 移植改动：
    //   ① 上游在页面里一边读 DOM 一边算周次；这里只做转换，输入是 extract.js 交出来的格子清单
    //      （所以 CI 里能用 Rhino 跑真实回归）。
    //   ② 上游按单元格 id 里的线性下标算星期与节次（day = floor(n / unitCount) + 1）。这条约定
    //      在同平台的其它适配器里说法不一致，所以这里优先读表格自己的结构：每行的「第N节」标签
    //      给出节次，标签列右边的第 k 列是星期 k。标签读不到时才退回上游那条 id 算法，
    //      并在 warnings 里说明。
    //   ③ 单元格文本改用「括号组 + 游标」切分。上游的固定三段正则遇到课程名本身带括号
    //      （「高等数学A(一)(课程代码)(教师)(周次,教室)」）会把整门课丢掉。
    //   ④ 教师 / 教室为空时留空（上游写「未知」「待定」，会被当成真姓名、真地点显示）。
    //   ⑤ 新增 warnings：推算的开学日与学期名、内置作息表、没解析出来的格子与课程。
    //   ⑥ 括号组里的纯数字（「大学英语(1)(课程代码)(李娜)(1-16周,教学楼B202)」里的「(1)」）
    //      是课程序号，不是周次 —— 只有格子里再没有别的周次组、或这一组后面已经接着下一门课
    //      的名字时才按周次认，否则会把同一门课的教师、教室、真实周次一起吃掉
    //      （上游的格式说明就是「课程名(序号)…」）。
    //   ⑦ 节次标签除「第1节」「第1-2节」外还认「第9,10节」这种顿号/逗号写法；连堂标签把
    //      下面几行的标签格合并掉时（rowSpan），被合并的行按标签里的下一节算，不再整行丢课；
    //      仍然定位不了节次的格子一律进 warnings（丢数据不许静默）。
    //   ⑧ 连堂标签只有一个起止时间（「第1-2节 08:20-09:55」），段内每一节的时间按该段的起止
    //      均分补出来；页面给不出的节次用内置作息表补上。两件事都写进 warnings —— 宿主的
    //      默认作息表只在 periodTimes 整个为空时才顶上来，缺一半会一路带进课表。
    var data = JSON.parse(__ncInput);
    var cells = data.cells || [];

    var DEFAULT_UNIT_COUNT = 11;
    var DEFAULT_TOTAL_WEEKS = 20;
    var MAX_TOTAL_WEEKS = 30;
    var MAX_WARNINGS = 20;
    var TERM_NAME_PREFIX = '马鞍山学院 ';

    // 该校作息时间（11 节），出自上游适配器内置的作息表。页面上的节次标签带时间时以后者为准。
    var PERIOD_TIMES = [
        { periodIndex: 1, start: '08:20', end: '09:05' },
        { periodIndex: 2, start: '09:10', end: '09:55' },
        { periodIndex: 3, start: '10:15', end: '11:00' },
        { periodIndex: 4, start: '11:05', end: '11:50' },
        { periodIndex: 5, start: '13:50', end: '14:35' },
        { periodIndex: 6, start: '14:40', end: '15:25' },
        { periodIndex: 7, start: '15:45', end: '16:30' },
        { periodIndex: 8, start: '16:35', end: '17:20' },
        { periodIndex: 9, start: '18:20', end: '19:05' },
        { periodIndex: 10, start: '19:10', end: '19:55' },
        { periodIndex: 11, start: '20:00', end: '20:45' }
    ];

    var SECTION_LABEL_RE = /^第\s*([0-9]{1,3}|[一二三四五六七八九十]{1,3})((?:\s*[-—~至,，、]\s*(?:[0-9]{1,3}|[一二三四五六七八九十]{1,3}))*)\s*节/;
    var SECTION_SEP_RE = /[-—~至,，、]/;
    var TIME_RANGE_RE = /([0-9]{1,2}:[0-9]{2})\s*[-—~至]\s*([0-9]{1,2}:[0-9]{2})/;
    var WEEK_CHARS_RE = /^[\s0-9单双周第、,，;；\-—~至()（）]*$/;
    var COURSE_CODE_RE = /^[0-9][0-9.\-_]*$/;
    var SEMESTER_NAME_RE = /20\d{2}\s*[-—~至]\s*20\d{2}\s*学年\s*第?\s*[0-9一二三四五六七八九]{1,2}\s*学期/;

    var warnings = [];

    function warn(message) {
        if (warnings.length >= MAX_WARNINGS) return;
        if (warnings.indexOf(message) >= 0) return;
        warnings.push(message);
    }

    function text(value) {
        if (value === null || value === undefined) return '';
        return String(value);
    }

    function tidy(value) {
        return text(value).replace(/\s+/g, ' ').trim();
    }

    function intOf(value, fallback) {
        var n = parseInt(value, 10);
        return isNaN(n) ? fallback : n;
    }

    function pad2(n) {
        return (n < 10 ? '0' : '') + n;
    }

    function isoOf(date) {
        return date.getFullYear() + '-' + pad2(date.getMonth() + 1) + '-' + pad2(date.getDate());
    }

    function parseIso(value) {
        var m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(text(value));
        if (!m) return null;
        return new Date(intOf(m[1], 1970), intOf(m[2], 1) - 1, intOf(m[3], 1));
    }

    function shiftDays(date, days) {
        return new Date(date.getFullYear(), date.getMonth(), date.getDate() + days);
    }

    function mondayIso(date) {
        return isoOf(shiftDays(date, -((date.getDay() + 6) % 7)));
    }

    // 开学日教务不给，只能推算，怎么推的必须写进 warnings
    function guessFirstDay(now, currentWeek) {
        var monday = mondayIso(now);
        if (currentWeek >= 1 && currentWeek <= MAX_TOTAL_WEEKS) {
            var base = parseIso(monday);
            return { iso: isoOf(shiftDays(base, -(currentWeek - 1) * 7)), week: currentWeek };
        }
        return { iso: monday, week: 0 };
    }

    // 学期名教务不给时按导入日期推：9 月到次年 1 月算第一学期，2-8 月算第二学期
    function academicTermName(now) {
        var year = now.getFullYear();
        var month = now.getMonth() + 1;
        if (month >= 9 || month === 1) {
            var start = month === 1 ? year - 1 : year;
            return start + '-' + (start + 1) + '学年第一学期';
        }
        return (year - 1) + '-' + year + '学年第二学期';
    }

    // 学期名优先用教务页面上写的（<select> 选中项或页面标题里的那一段）
    function termNameFromPage(title, labels) {
        var candidates = [];
        var i;
        for (i = 0; i < labels.length; i++) candidates.push(labels[i]);
        candidates.push(title);
        for (i = 0; i < candidates.length; i++) {
            var match = SEMESTER_NAME_RE.exec(tidy(candidates[i]));
            if (match) return tidy(match[0]);
        }
        return '';
    }

    function sectionOf(token) {
        var s = String(token || '');
        if (/^[0-9]+$/.test(s)) return parseInt(s, 10);
        var digits = { '一': 1, '二': 2, '三': 3, '四': 4, '五': 5, '六': 6, '七': 7, '八': 8, '九': 9 };
        if (s === '十') return 10;
        var ten = s.indexOf('十');
        if (ten >= 0) {
            var tens = ten > 0 ? digits[s.charAt(ten - 1)] : 1;
            var ones = s.length > ten + 1 ? digits[s.charAt(ten + 1)] : 0;
            if (!tens || (s.length > ten + 1 && !ones)) return 0;
            return tens * 10 + ones;
        }
        return digits[s] || 0;
    }

    // 「第N节」标签里的节次编号，按顺序列出来：「第3节」→ [3]、「第1-2节」→ [1, 2]、
    // 「第9,10节」→ [9, 10]、「第9、10、11节」→ [9, 10, 11]。
    // 认不出来（标签写成「5-6节」「上午」之类）就返回空数组 —— 调用方必须为此出声，别静默丢课。
    function sectionsFromLabel(labelText) {
        var match = SECTION_LABEL_RE.exec(text(labelText));
        if (!match) return [];
        var out = [];
        var first = sectionOf(match[1]);
        if (first) out.push(first);
        var rest = match[2] ? match[2].split(SECTION_SEP_RE) : [];
        for (var i = 0; i < rest.length; i++) {
            var value = sectionOf(tidy(rest[i]));
            if (value) out.push(value);
        }
        return out;
    }

    // 一个标签格 rowSpan 盖住若干行时，第 offset 行对应标签里的第几个节次：
    // 标签里的编号不够用（「第1节」却盖了两行）就按顺序往后推一节。
    function sectionAtOffset(tokens, offset) {
        if (offset < tokens.length) return tokens[offset];
        return tokens[tokens.length - 1] + (offset - tokens.length + 1);
    }

    // "08:20" ↔ 当天的第几分钟（只用来把连堂段的起止时间均分给段内各节）
    function minutesOf(value) {
        var match = /^([0-9]{1,2}):([0-9]{2})$/.exec(text(value));
        if (!match) return -1;
        return intOf(match[1], -1) * 60 + intOf(match[2], -1);
    }

    function hhmmOf(minutes) {
        return pad2(Math.floor(minutes / 60)) + ':' + pad2(minutes % 60);
    }

    // 一个连堂段（「第1-2节 08:20-09:55」）里每一节的时间：把段的起止时间按节数均分。
    // 分不出来（段太短、时间格式不对）就返回空数组，让调用方退回内置作息表。
    function splitSectionTime(startText, endText, count) {
        var start = minutesOf(startText);
        var end = minutesOf(endText);
        if (start < 0 || end <= start || !(count >= 1)) return [];
        var out = [];
        var total = end - start;
        for (var k = 0; k < count; k++) {
            var from = start + Math.floor(total * k / count);
            var to = k === count - 1 ? end : start + Math.floor(total * (k + 1) / count);
            if (to <= from) return [];
            out.push({ start: hhmmOf(from), end: hhmmOf(to) });
        }
        return out;
    }

    // 把一串编号写成给人看的「2、3、4 节」（太长就只列前几个，并说清一共几个）
    function numbersOf(values, max, unit) {
        var head = [];
        for (var i = 0; i < values.length && i < max; i++) head.push(values[i]);
        var tail = unit ? ' ' + unit : '';
        if (values.length > head.length) return head.join('、') + ' 等 ' + values.length + ' ' + unit;
        return head.join('、') + tail;
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

    // 周次文本："2-6" / "双周2-6、10" / "2-3、6-7、9-14" / "2-6周(双)" 都可能。
    // 整串开头的「单周/双周」是全局修饰（"双周2-6、10"），写在段里的只作用于那一段
    // （"1-8周、10-16周(双)" 只有后半段是双周 —— 上游把全局和分段混为一谈）。
    function weeksFromText(source) {
        var s = text(source).replace(/第/g, '');
        var globalType = '';
        var head = /^\s*(单|双)\s*周?/.exec(s);
        if (head) {
            globalType = head[1];
            s = s.replace(/^\s*(单|双)\s*周?/, '');
        }
        var parts = s.split(/[、,，;；]/);
        var weeks = [];
        for (var i = 0; i < parts.length; i++) {
            var part = parts[i];
            var marker = /(单|双)/.exec(part);
            var type = marker ? marker[1] : globalType;
            var clean = part.replace(/单周|双周|[单双]/g, '').replace(/周/g, '');
            var range = /(\d{1,2})\s*[-—~至]\s*(\d{1,2})/.exec(clean);
            var start;
            var end;
            if (range) {
                start = parseInt(range[1], 10);
                end = parseInt(range[2], 10);
            } else {
                var single = /(\d{1,2})/.exec(clean);
                if (!single) continue;
                start = parseInt(single[1], 10);
                end = start;
            }
            for (var w = start; w <= end; w++) {
                if (type === '单' && w % 2 === 0) continue;
                if (type === '双' && w % 2 === 1) continue;
                weeks.push(w);
            }
        }
        return uniqueSorted(weeks);
    }

    // 周次集合 → 极大段（连续段用 ALL，隔周段用 ODD / EVEN，落单的一周也是 ALL）
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

    // 扫出文本里所有括号组（含一层嵌套，教室可能是「数智楼(3号实验楼)309」）。
    // 半角括号才是格式的一部分，课程名里的全角括号（「高等数学（二）」）留在名字里。
    function parenGroups(source) {
        var s = text(source);
        var out = [];
        var i = 0;
        while (i < s.length) {
            if (s.charAt(i) !== '(') {
                i++;
                continue;
            }
            var depth = 0;
            var j = i;
            for (; j < s.length; j++) {
                var ch = s.charAt(j);
                if (ch === '(') depth++;
                else if (ch === ')') {
                    depth--;
                    if (depth === 0) break;
                }
            }
            if (depth !== 0) break;
            out.push({ start: i, end: j + 1, text: s.slice(i + 1, j) });
            i = j + 1;
        }
        return out;
    }

    // 一段文字是不是周次（"1-16" / "单周1-9" / "双周2-6、10"）。必须只由周次该有的字符组成
    // 且含数字，否则「(张伟,李娜)」这种多教师、以及「202620271.11202001.002」这种课程代码
    // 都会被人当成周次。
    function isWeekList(value) {
        var s = text(value);
        if (!WEEK_CHARS_RE.test(s)) return false;
        if (!/[0-9]/.test(s)) return false;
        return weeksFromText(s).length > 0;
    }

    // 只由一个数字组成的括号组（「(1)」）。它既可能是周次（第 1 周），也可能是课程名后面的
    // 序号 —— 上游的格式就是「课程名(序号) … (周次,教室)」，所以后者更常见；认错的话，
    // 同一门课的教师、教室、真实周次会被后面的解析一起吃掉（见 parseCourses 里怎么取舍）。
    function isBareWeekNumber(value) {
        return /^[0-9]{1,2}$/.test(tidy(value));
    }

    // 一个括号组是不是「周次,教室」。逗号从右往左试：周次段只可能由周次字符组成，
    // 教室段随便写（「教1-101,东区」也不会被切成周次）。上游固定切第一个逗号，
    // 遇到周次里带逗号的写法会把教室吃进周次。
    function weekSpecOf(inner) {
        var s = text(inner);
        var cuts = [];
        for (var i = 0; i < s.length; i++) {
            var ch = s.charAt(i);
            if (ch === ',' || ch === '，') cuts.push(i);
        }
        for (var k = cuts.length - 1; k >= 0; k--) {
            var head = s.slice(0, cuts[k]);
            if (isWeekList(head)) return { weeks: head, room: s.slice(cuts[k] + 1) };
        }
        if (isWeekList(s)) return { weeks: s, room: '' };
        return null;
    }

    // 一个格子串了多门课：从右往左数「课程代码 / 教师 / 周次教室」三件套，游标推到本门课之后。
    // 课程名本身可能带括号，所以不能像上游那样从左往右死数三组括号。
    function parseCourses(source) {
        var s = text(source);
        var groups = parenGroups(s);
        var out = [];
        var cursor = 0;
        var pending = [];
        var i;
        var specs = [];
        var strong = [];
        var strongAfter = [];
        // 先给每个括号组定性：是不是周次、是不是「一定能认出来」的周次（带 周/范围/逗号）。
        // 裸数字组（「(1)」）只是候选，要看它后面还有没有硬周次组才知道它是周次还是序号。
        for (i = 0; i < groups.length; i++) {
            var groupSpec = weekSpecOf(groups[i].text);
            specs.push(groupSpec);
            strong.push(!!groupSpec && !isBareWeekNumber(groups[i].text));
        }
        var seenStrong = false;
        for (i = groups.length - 1; i >= 0; i--) {
            strongAfter[i] = seenStrong;
            if (strong[i]) seenStrong = true;
        }
        for (i = 0; i < groups.length; i++) {
            var group = groups[i];
            var spec = specs[i];
            // 裸数字组（「(1)」）要判断它是周次还是课程序号。当周次认只有两种情况：
            //   ① 整个格子里再没有别的周次组 —— 没得挑，只能按周次用；
            //   ② 这一组后面紧跟着**下一门课的名字**（或已经到格子末尾）—— 这门课的周次写完了，
            //      接下来是新的一门课（「课程A(张三)(1)课程B(李四)(2-5周,教室)」）。
            // 都不成立就说明它夹在一门课的「(序号)(课程代码)(教师)…」这一串里，是序号：
            // 认成周次的话，后面那些教师、教室、真实周次会被一起吃掉。
            if (spec && isBareWeekNumber(group.text) && strongAfter[i]) {
                var isLastGroup = i + 1 >= groups.length;
                var gap = isLastGroup ? '' : s.slice(group.end, groups[i + 1].start);
                if (!isLastGroup && !/[^\s]/.test(gap)) spec = null;
            }
            if (!spec) {
                pending.push(group);
                continue;
            }
            var code = null;
            var teacher = null;
            if (pending.length) {
                var last = pending[pending.length - 1];
                var before = pending.length > 1 ? pending[pending.length - 2] : null;
                if (COURSE_CODE_RE.test(tidy(last.text))) {
                    code = last;
                    teacher = before;
                } else {
                    teacher = last;
                    code = before;
                }
            }
            var nameEnd = code ? code.start : (teacher ? teacher.start : group.start);
            var name = tidy(s.slice(cursor, nameEnd));
            cursor = group.end;
            pending = [];
            // 名字末尾残留的「(1)」是课程序号（同一门课在不同格子里的序号可能不一样，
            // 留着会把一门课拆成几门）。上游 cleanCourseName 也删它，只是它还顺手把全角
            // 括号里的课名一起删了（「体育（一）」→「体育」），这里只删半角括号里的纯数字。
            name = name.replace(/\([0-9]{1,3}\)$/, '').trim();
            if (!name) continue;
            out.push({
                name: name,
                teacher: tidy(teacher ? teacher.text : ''),
                location: tidy(spec.room),
                weeks: spec.weeks
            });
        }
        return out;
    }

    var i;
    var ordered = [];
    for (i = 0; i < cells.length; i++) ordered.push(cells[i]);
    ordered.sort(function (a, b) {
        return (intOf(a.row, -1) - intOf(b.row, -1)) || (intOf(a.col, -1) - intOf(b.col, -1));
    });

    if (!ordered.length) {
        // 表格没找到：课表可能被画在图片 / 画布上，交给应用的 OCR 链路校对
        if (data.image) {
            return JSON.stringify({
                specVersion: 1,
                kind: 'image',
                ocrAssisted: true,
                images: [data.image]
            });
        }
        throw new Error('这一页里没找到课表（既没有课表表格，也没有课表图片）。请先登录教务系统并打开「我的课表」，再点「提取课表」');
    }

    // 每行的「第N节」标签：既给出这一行的节次（支持「第1-2节」这种跨节标签、「第9,10节」
    // 这种多节写法，以及标签里的上下课时间），也给出星期列的起点 —— 标签列右边的第 k 列
    // 就是星期 k。
    var labels = {};
    var labelCells = [];
    var labelRows = 0;
    var maxLabelPeriod = 0;
    var tableLabelCol = -1;
    for (i = 0; i < ordered.length; i++) {
        var labelCell = ordered[i];
        if (intOf(labelCell.colSpan, 1) > 1) continue;
        if (intOf(labelCell.row, -1) < 0) continue;
        var labelText = tidy(labelCell.text);
        var labelSections = sectionsFromLabel(labelText);
        if (!labelSections.length) continue;
        var labelSpan = intOf(labelCell.rowSpan, 1);
        if (!(labelSpan >= 1)) labelSpan = 1;
        var labelTime = TIME_RANGE_RE.exec(labelText);
        labelCells.push({
            row: intOf(labelCell.row, -1),
            col: intOf(labelCell.col, 0),
            spanRows: labelSpan,
            tokens: labelSections,
            time: labelTime ? { start: labelTime[1], end: labelTime[2] } : null
        });
        if (tableLabelCol < 0) tableLabelCol = intOf(labelCell.col, 0);
        for (var s = 0; s < labelSections.length; s++) {
            if (labelSections[s] > maxLabelPeriod) maxLabelPeriod = labelSections[s];
        }
    }

    // 标签格自己那一行：标签里的第一节就是这一行的节次。标签格把一行写完（rowSpan=1）时，
    // 「第1-2节」描述的是整行的跨度，所以这一行的课可以占满 1-2 节；标签格被合并到几行
    // （rowSpan>1）时每一行只对应其中的一节，不能再往后扩。
    for (i = 0; i < labelCells.length; i++) {
        var ownCell = labelCells[i];
        if (labels[ownCell.row]) continue;
        labels[ownCell.row] = {
            section: ownCell.tokens[0],
            last: ownCell.spanRows > 1 ? ownCell.tokens[0] : ownCell.tokens[ownCell.tokens.length - 1],
            col: ownCell.col
        };
    }
    // 被连堂标签盖住的行（它们没有自己的标签格）：按标签里的下一个节次补上 ——
    // 「第1-2节」盖住两行时第二行就是第 2 节，不再整行读不到节次。
    for (i = 0; i < labelCells.length; i++) {
        var coverCell = labelCells[i];
        for (var dr = 1; dr < coverCell.spanRows; dr++) {
            var coveredRow = coverCell.row + dr;
            if (labels[coveredRow]) continue;
            var coveredSection = sectionAtOffset(coverCell.tokens, dr);
            labels[coveredRow] = { section: coveredSection, last: coveredSection, col: coverCell.col };
        }
    }
    labelRows = 0;
    for (var labelRowKey in labels) {
        if (labels.hasOwnProperty(labelRowKey)) labelRows++;
    }

    // 节次标签一个都读不到：退回上游那条按单元格 id 算线性下标的算法
    var unitCount = intOf(data.unitCount, 0);
    if (!(unitCount >= 1 && unitCount <= 30)) unitCount = DEFAULT_UNIT_COUNT;
    var useCellIds = labelRows === 0;
    if (useCellIds) {
        warn('没能从课表里读出「第N节」标签，已退回按单元格编号推断星期与节次，请重点核对星期和节次');
    }

    function positionOf(cell, span) {
        if (!useCellIds) {
            var label = labels[intOf(cell.row, -1)];
            if (!label) return null;
            var col = intOf(cell.col, -1);
            if (col === label.col) return null;
            var end = label.section + span - 1;
            if (label.last > end) end = label.last;
            return { day: col - label.col, startPeriod: label.section, endPeriod: end };
        }
        var idMatch = /^TD(\d+)_/.exec(text(cell.id));
        if (!idMatch) return null;
        var index = parseInt(idMatch[1], 10);
        var start = (index % unitCount) + 1;
        return { day: Math.floor(index / unitCount) + 1, startPeriod: start, endPeriod: start + span - 1 };
    }

    var courses = [];
    var byCourse = {};
    var unparsedCells = 0;
    var noWeekCourses = 0;
    var outOfRangeCells = 0;
    var unpositioned = [];

    // 定位不了节次的格子（那一行没有能认出来的「第N节」标签）里如果本来有课，就是**丢课**，
    // 要进 warnings。表头、「第N节」标签格这些格子本来就解析不出课程，不能拿它们刷 warnings，
    // 所以先看解析结果再决定要不要出声。
    function dayOfUnpositioned(cell) {
        if (tableLabelCol >= 0) {
            var day = intOf(cell.col, -1) - tableLabelCol;
            return day >= 1 && day <= 7 ? day : 0;
        }
        var idMatch = /^TD(\d+)_/.exec(text(cell.id));
        if (!idMatch) return 0;
        return Math.floor(parseInt(idMatch[1], 10) / unitCount) + 1;
    }

    for (i = 0; i < ordered.length; i++) {
        var cell = ordered[i];
        var span = intOf(cell.rowSpan, 1);
        if (!(span >= 1)) span = 1;
        var position = positionOf(cell, span);
        var raw = tidy(cell.text);
        if (!raw) raw = tidy(cell.title);
        if (!raw) continue;
        if (!position) {
            if (parseCourses(raw).length) {
                unpositioned.push({ row: intOf(cell.row, -1), day: dayOfUnpositioned(cell) });
            }
            continue;
        }
        var parsed = parseCourses(raw);
        if (!parsed.length) {
            unparsedCells++;
            continue;
        }
        if (!(position.day >= 1 && position.day <= 7) || position.startPeriod < 1) {
            outOfRangeCells++;
            continue;
        }
        for (var p = 0; p < parsed.length; p++) {
            var course = parsed[p];
            var weeks = weeksFromText(course.weeks);
            if (!weeks.length) {
                noWeekCourses++;
                continue;
            }
            var key = course.name + '|' + course.teacher;
            if (!byCourse[key]) {
                byCourse[key] = { name: course.name, teacher: course.teacher || null, note: null, blocks: [] };
                courses.push(byCourse[key]);
            }
            var runs = runsOf(weeks);
            for (var r = 0; r < runs.length; r++) {
                byCourse[key].blocks.push({
                    dayOfWeek: position.day,
                    startPeriod: position.startPeriod,
                    endPeriod: position.endPeriod,
                    startWeek: runs[r].start,
                    endWeek: runs[r].end,
                    weekType: runs[r].weekType,
                    location: course.location || null
                });
            }
        }
    }

    if (!courses.length) {
        throw new Error('课表页面上没有解析到任何课程，请确认「我的课表」里已经有排课，然后再点「提取课表」');
    }

    var maxWeek = 0;
    var c;
    for (c = 0; c < courses.length; c++) {
        var blocks = courses[c].blocks;
        blocks.sort(function (a, b) {
            return (a.dayOfWeek - b.dayOfWeek) || (a.startPeriod - b.startPeriod) ||
                (a.startWeek - b.startWeek) || (a.endWeek - b.endWeek);
        });
        for (var b = 0; b < blocks.length; b++) {
            if (blocks[b].endWeek > maxWeek) maxWeek = blocks[b].endWeek;
        }
    }

    var totalWeeks = maxWeek > DEFAULT_TOTAL_WEEKS ? maxWeek : DEFAULT_TOTAL_WEEKS;
    if (totalWeeks > MAX_TOTAL_WEEKS) totalWeeks = MAX_TOTAL_WEEKS;
    var clampedWeeks = false;
    if (totalWeeks < maxWeek) {
        clampedWeeks = true;
        for (c = 0; c < courses.length; c++) {
            var list = courses[c].blocks;
            for (var k = 0; k < list.length; k++) {
                if (list[k].endWeek > totalWeeks) list[k].endWeek = totalWeeks;
                if (list[k].startWeek > totalWeeks) list[k].startWeek = totalWeeks;
            }
        }
    }

    // 节次时间：页面上读出来的优先。连堂标签（「第1-2节 08:20-09:55」）只有整段的起止时间，
    // 段内每一节按起止均分补出来 —— 不补的话第 2、4、6… 节就没有上下课时间，而宿主的默认
    // 作息表只在 periodTimes 整个为空时才顶上来，缺一半会一路带进课表。
    // 页面给不出时间的节次用内置作息表补，两件事都写进 warnings。
    var pageTimes = {};
    var timedPagePeriods = 0;
    var splitSpans = 0;
    for (i = 0; i < labelCells.length; i++) {
        var timeCell = labelCells[i];
        if (!timeCell.time) continue;
        var parts = splitSectionTime(timeCell.time.start, timeCell.time.end, timeCell.tokens.length);
        if (!parts.length) continue;
        if (timeCell.tokens.length > 1) splitSpans++;
        for (var t = 0; t < timeCell.tokens.length; t++) {
            if (pageTimes[timeCell.tokens[t]]) continue;
            pageTimes[timeCell.tokens[t]] = parts[t];
            timedPagePeriods++;
        }
    }

    var maxPeriod = maxLabelPeriod;
    for (c = 0; c < courses.length; c++) {
        for (var m = 0; m < courses[c].blocks.length; m++) {
            if (courses[c].blocks[m].endPeriod > maxPeriod) maxPeriod = courses[c].blocks[m].endPeriod;
        }
    }
    // 一个「第N节」标签都没读出来时（退回按单元格编号定位），页面上没有节次信息，
    // 作息只能整体用内置表 —— 这张表就是该校的 11 节，别只发课程用到的那几节，
    // 课表里其它节次到时候也会没有上下课时间。
    if (!labelCells.length && maxPeriod < PERIOD_TIMES.length) maxPeriod = PERIOD_TIMES.length;
    if (!(maxPeriod >= 1)) maxPeriod = PERIOD_TIMES.length;

    var periodTimes = [];
    var presetPeriods = [];
    var untimedPeriods = [];
    for (var period = 1; period <= maxPeriod; period++) {
        var pageTime = pageTimes[period];
        if (pageTime) {
            periodTimes.push({ periodIndex: period, start: pageTime.start, end: pageTime.end });
            continue;
        }
        var presetTime = null;
        for (var pt = 0; pt < PERIOD_TIMES.length; pt++) {
            if (PERIOD_TIMES[pt].periodIndex === period) presetTime = PERIOD_TIMES[pt];
        }
        if (presetTime) {
            periodTimes.push(presetTime);
            presetPeriods.push(period);
        } else {
            untimedPeriods.push(period);
        }
    }

    if (splitSpans) {
        warn('页面上的节次标签把两节写在一起（如「第1-2节 08:20-09:55」），段内每一节的上下课时间已按该段的起止时间均分，如与教务不一致请在节次设置里调整');
    }
    if (presetPeriods.length) {
        if (timedPagePeriods) {
            warn('第 ' + numbersOf(presetPeriods, 6, '节') + '的上下课时间页面上没有，用的是适配器内置的马鞍山学院作息表，如与教务不一致请在节次设置里调整');
        } else {
            warn('节次时间用的是适配器内置的马鞍山学院作息表（' + PERIOD_TIMES.length + ' 节），如与教务不一致请在节次设置里调整');
        }
    }
    if (untimedPeriods.length) {
        warn('第 ' + numbersOf(untimedPeriods, 6, '节') + '的上下课时间页面和内置作息表里都没有，课表里这几节不会显示上下课时间');
    }

    var now = parseIso(data.today) || new Date();
    var firstDay = guessFirstDay(now, intOf(data.currentWeek, 0));
    if (firstDay.week) {
        warn('开学日期教务没有提供，已按页面上显示的「第 ' + firstDay.week + ' 周」反推为 ' + firstDay.iso + '，请在学期管理里核对');
    } else {
        warn('开学日期教务没有提供，已按最近的周一（' + firstDay.iso + '）推算，请在学期管理里核对');
    }

    var termName = termNameFromPage(data.title, data.semesterLabels || []);
    if (!termName) {
        termName = TERM_NAME_PREFIX + academicTermName(now);
        warn('学期名教务没有提供，已按导入日期推算为「' + termName + '」，如与实际不符可在学期管理里改名');
    }

    if (maxWeek < DEFAULT_TOTAL_WEEKS) {
        warn('学期总周数教务没有提供，已按 ' + totalWeeks + ' 周计（课表里最大的周次是 ' + maxWeek + ' 周），如校历不同请在学期管理里调整');
    }

    if (unparsedCells) {
        warn('有 ' + unparsedCells + ' 个课表格子没能解析出课程（文字格式与预期不符），已跳过，请核对课表');
    }
    if (clampedWeeks) {
        warn('课表里出现了超过 ' + MAX_TOTAL_WEEKS + ' 周的周次，已按 ' + totalWeeks + ' 周截断，请核对课表');
    }
    if (noWeekCourses) {
        warn('有 ' + noWeekCourses + ' 门课程没能解析出周次，已跳过，请核对课表');
    }
    if (outOfRangeCells) {
        warn('有 ' + outOfRangeCells + ' 个格子落在星期或节次范围之外，已跳过，请核对课表');
    }
    if (unpositioned.length) {
        // 定位不到节次的格子（解析得出课程，但那一行没有能认出来的「第N节」标签）：
        // 说不出是哪一格就等于静默丢课，所以至少把星期（或行号）报出来。
        var days = [];
        var rows = [];
        for (i = 0; i < unpositioned.length; i++) {
            var spot = unpositioned[i];
            if (spot.day >= 1 && spot.day <= 7 && days.indexOf(spot.day) < 0) days.push(spot.day);
            if (spot.row >= 0 && rows.indexOf(spot.row + 1) < 0) rows.push(spot.row + 1);
        }
        days.sort(function (a, b) { return a - b; });
        var where = days.length ? '（星期 ' + numbersOf(days, 7, '') + '）' :
            (rows.length ? '（表格第 ' + numbersOf(rows, 3, '行') + '）' : '');
        warn('课表里有 ' + unpositioned.length + ' 格课没能读出「第N节」标签' + where + '，已跳过，请核对课表');
    }

    return JSON.stringify({
        specVersion: 1,
        kind: 'schedule',
        ocrAssisted: false,
        warnings: warnings,
        terms: [
            {
                name: termName,
                firstDay: firstDay.iso,
                totalWeeks: totalWeeks,
                periodTimes: periodTimes,
                courses: courses
            }
        ]
    });
})()
