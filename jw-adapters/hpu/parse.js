(function () {
    // 河南理工大学教务适配器（树维 EAMS 平台）—— 第二步：教务原始数据 → 空课课表载荷。
    //
    // 移植自 shiguang_warehouse 的 HPU/hpu.js
    //   https://github.com/XingHeYuZhuan/shiguang_warehouse  （MIT，上游作者 ca1q1an）
    //   上游快照 e62554a4034386b893bcd6813c7b2b64f8c730a3（2026-09-12）
    //
    // 平台：树维 EAMS（/eams/，上海树维信息科技有限公司 / SupWisdom）。上游自称
    //   「河南理工大学树维教务系统(eams架构)」，与本批其余 11 件同族：同一套三连接口、
    //   同一套 TaskActivity 内嵌课程脚本、同一套 index = 星期 * unitCount + 节次 的寻址。
    //   （强智走 /jsxsd/，不是这一族；批次一/三里把 /eams/ 写成强智的地方，本批统一订正。）
    //
    // 上游把「取数 + 解析 + 存配置」揉在一个自执行脚本里；这里切成两段，本文件只做纯转换：
    //   不碰页面、不发请求，输入是 extract.js 交出来的教务原始数据 —— CI 就是用 Rhino 跑这一段。
    //
    // 移植改动（逐条，AUDIT.md 里有对应说明与用例）：
    //   ① ES6 → ES5：上游 100% async/await + 箭头函数 + 模板串，本文件一段都没有（纯同步函数）
    //   ② 上游用 Function("return (" + raw + ")")() 求值 semesterCalendar 的响应 —— 那段字符串来自
    //      网络取回的文本，命中移植手册 §5 第 6 条（不 eval 远程代码）。这里改成对响应文本做定向
    //      取值（按 {...} 切记录、按字段名取值），全程不执行取回来的任何字符串
    //   ③ 上游用 /\(([^]*?)\)\s*;/ 截 TaskActivity 的参数，参数里出现 ) 就截歪（args[1] 写成
    //      xxx.join(",") 时正是如此）。这里跳过引号、按括号深度找配对
    //   ④ 上游的 splitJsArgs 会把空参数位整个丢掉（逗号连逗号时后面的参数位整体前移，
    //      教师/教室/周次全部错位）。这里保留空位，只在末尾修剪
    //   ⑤ 教师：上游写死「未知教师」、教室写死「待定」，在课表里会被当成真姓名真地点显示
    //      （手册 §4.7）。这里拿不到就留空（null），并在 warnings 里说出有几处没解析出来。
    //      另外，args[1] 是 xxx.join(",") 这种表达式时除了 teachers 数组，上游只找 var teachers，
    //      这里把 actTeachers / taskTeachers 两种同族写法一起认
    //   ⑥ 课程名的收尾括号：上游一律删掉，于是「高等数学A(一)」会变成「高等数学A」。这里只删
    //      「像课程号 / 教学班序号」的那种（括号里有数字、且没有汉字），「(一)」保留
    //   ⑦ unitCount：上游读不到就写 0，随后整数除零得到 Infinity，整张课表被静默丢空。
    //      这里读不到用 11（上游注释里 HPU 的实测值），并且必须写进 warnings
    //   ⑧ 周次位图按本批统一口径：下标 i 就是第 i 周，下标 0 是占位符。上游原文本来就是
    //      「i >= 1」，与同批 12 件收敛后的口径一致；下标 0 为 1 时出一条 warnings，
    //      绝不产出「第 0 周」
    //   ⑨ 位图里超过 30 周的位丢弃（载荷上限是 30），丢弃数量写进 warnings（本批检查表第 7 条）
    //   ⑩ 作息时间：上游用 DOMParser 读 #manualArrangeCourseTable 行内的「第N节 (HH:mm-HH:mm)」。
    //      parse.js 在 Rhino 里跑，没有 DOMParser，这里改成把 HTML 切成单元格后按同样两条特征
    //      （第N节 + 时间区间，或 id="0_N" + 时间区间）读；读不到才回落空课内置的 12 节作息表，
    //      并且一定写进 warnings
    //   ⑪ 开学日：上游完全不写（它只在提示里让用户自己去 App 里设）。这里按手册 §4.3 从
    //      semesterCalendar 的学期起止日期推 —— 第 1 周从起止日期那一天所在周的周一算起；
    //      教务没给日期就按学期序号推算，两种情况都写进 warnings（手册 §4.2）
    //   ⑫ 学期：上游弹窗让用户选（showSingleSelection）。这里自动取当前学期（教务页面标出的那个，
    //      或学期列表响应里的 semesterId），只导入这一个学期（手册 §3 第 1 步）
    //   ⑬ 上游的 showToast / notifyTaskCompletion / saveImportedCourses / savePresetTimeSlots
    //      这些推式桥调用一个都没有移植；我们的载荷是拉式返回的
    //   ⑭ 上游「当前页面已是渲染好的课表页 → 直接解析 DOM」那条兜底没有移植：本适配器只走接口链。
    //      理由与代价写在 AUDIT.md「已知边界」里
    //   ⑮ 节次合并：上游判据是「上一段结束节 + 1 >= 本段开始节」，会把完全重复的排课也吸进上一段，
    //      改成严格相邻（+1 ===）；重复的那些行随后被「完全重复的安排只留一条」丢掉，结果一样

    var data = JSON.parse(typeof __ncInput === 'undefined' ? '{}' : __ncInput);
    var semesterRaw = typeof data.semesterRaw === 'string' ? data.semesterRaw : '';
    var courseHtml = typeof data.courseHtml === 'string' ? data.courseHtml : '';
    var pickInfo = data.semesterPick && typeof data.semesterPick === 'object' ? data.semesterPick : {};
    var pickedId = text(pickInfo.id);
    var todayIso = isoOfAnyValue(data.today) || localTodayIso();

    // 复合键（课名 + 教师）的分隔符取 NUL。用 String.fromCharCode 取，源码里不出现控制字符、
    // 也不出现转义序列 —— 第一批有两个适配器把转义序列落成了真的 NUL 字节，文件被 grep 当二进制看。
    var SEP = String.fromCharCode(0);

    var MAX_WEEK = 30;              // 载荷校验：totalWeeks 与 startWeek / endWeek 都在 1..30
    var MAX_PERIOD = 20;            // 单日节次上限：超过它一定是脏数据
    var DEFAULT_UNIT_COUNT = 11;    // 上游注释里 HPU 实测 unitCount=11；读不到时用它并写进 warnings
    var MAX_UNIT_COUNT = 20;        // 读到的节次总数超过它一定是脏数据，按读不到处理
    var MIN_BITMAP_LENGTH = 8;      // 短于这个长度的全 0/1 串多半不是周次位图
    var FALLBACK_TOTAL_WEEKS = 20;  // 教务不给学期起止日期时的总周数（空课常见口径）
    var MAX_WARNINGS = 20;          // 载荷校验：warnings ≤ 20 条
    var MAX_WARNING_CHARS = 200;    // 载荷校验：每条 ≤ 200 字
    var RESOLVE_WINDOW = 4000;      // 往前找 var teachers / var courseName 的窗口（字符）

    // 空课内置节次表（:core:model 的 DefaultPeriodTimes，12 节），与 Kotlin 侧逐字一致：
    // 课表表头读不到作息时交这份，并写进 warnings —— 它是空课给的，不是教务给的。
    var BUILTIN_PERIOD_TIMES = [
        { periodIndex: 1, start: '08:00', end: '08:45' },
        { periodIndex: 2, start: '08:55', end: '09:40' },
        { periodIndex: 3, start: '10:00', end: '10:45' },
        { periodIndex: 4, start: '10:55', end: '11:40' },
        { periodIndex: 5, start: '14:00', end: '14:45' },
        { periodIndex: 6, start: '14:55', end: '15:40' },
        { periodIndex: 7, start: '16:00', end: '16:45' },
        { periodIndex: 8, start: '16:55', end: '17:40' },
        { periodIndex: 9, start: '18:30', end: '19:15' },
        { periodIndex: 10, start: '19:25', end: '20:10' },
        { periodIndex: 11, start: '20:20', end: '21:05' },
        { periodIndex: 12, start: '21:15', end: '22:00' }
    ];

    // 推算开学日的锚点：第一学期多在 9 月初、第二学期多在 2 月下旬、夏季学期 7 月初。
    // 别把锚点直接当 firstDay：手册 §4.3 要求回退到那一周的周一。
    var TERM_ANCHOR = {
        first: { month: 9, day: 1, yearOffset: 0, rule: '第一学期按 9 月 1 日所在周的周一' },
        second: { month: 2, day: 20, yearOffset: 1, rule: '第二学期按 2 月 20 日所在周的周一' },
        third: { month: 7, day: 1, yearOffset: 1, rule: '第三学期按 7 月 1 日所在周的周一' }
    };

    var WEEKDAY_CN = ['周一', '周二', '周三', '周四', '周五', '周六', '周日'];

    // U+00A0（不换行空格）用 String.fromCharCode 取：源码里不出现转义序列，也不出现这个字符本身
    var NBSP_RE = new RegExp(String.fromCharCode(160), 'g');

    // ---------- 小工具 ----------
    function text(value) {
        if (value === null || value === undefined) return '';
        return String(value).replace(/\s+/g, ' ').trim();
    }

    function pad2(n) {
        return (n < 10 ? '0' : '') + n;
    }

    function isoOfDate(date) {
        if (!date || isNaN(date.getTime())) return null;
        return date.getUTCFullYear() + '-' + pad2(date.getUTCMonth() + 1) + '-' + pad2(date.getUTCDate());
    }

    function isoFromParts(year, month, day) {
        var date = new Date(Date.UTC(year, month - 1, day));
        if (date.getUTCFullYear() !== year || date.getUTCMonth() + 1 !== month || date.getUTCDate() !== day) {
            return null;
        }
        return isoOfDate(date);
    }

    // 任意形态的值 → ISO 日期：
    //   "2026-09-07" / "2026/9/7" / "2026.9.7" / "2026年9月7日" / 夹在更长串里的日期
    //   / 13 位毫秒时间戳（按 UTC 换算）/ 10 位秒时间戳
    // 认不出来就返回 null，调用方据此回落推算并写进 warnings。
    function isoOfAnyValue(value) {
        var s = text(value);
        if (!s) return null;
        var m = /^(\d{4})[-/.年](\d{1,2})[-/.月](\d{1,2})/.exec(s);
        if (!m) m = /(\d{4})[-/.年](\d{1,2})[-/.月](\d{1,2})/.exec(s);
        if (m) return isoFromParts(parseInt(m[1], 10), parseInt(m[2], 10), parseInt(m[3], 10));
        if (/^\d{13,16}$/.test(s)) return isoOfDate(new Date(parseInt(s, 10)));
        if (/^\d{10}$/.test(s)) return isoOfDate(new Date(parseInt(s, 10) * 1000));
        return null;
    }

    // 该日期所在周的周一（用 UTC 算，避免时区把日期挪一天）
    function mondayOnOrBeforeIso(iso) {
        if (!iso) return null;
        var date = new Date(Date.UTC(
            parseInt(iso.substring(0, 4), 10),
            parseInt(iso.substring(5, 7), 10) - 1,
            parseInt(iso.substring(8, 10), 10)
        ));
        var offset = (date.getUTCDay() + 6) % 7;
        return isoOfDate(new Date(date.getTime() - offset * 86400000));
    }

    function mondayOnOrBefore(year, month, day) {
        var date = new Date(Date.UTC(year, month - 1, day));
        var offset = (date.getUTCDay() + 6) % 7;
        return isoOfDate(new Date(date.getTime() - offset * 86400000));
    }

    function weekdayCnOf(iso) {
        var date = new Date(Date.UTC(
            parseInt(iso.substring(0, 4), 10),
            parseInt(iso.substring(5, 7), 10) - 1,
            parseInt(iso.substring(8, 10), 10)
        ));
        return WEEKDAY_CN[(date.getUTCDay() + 6) % 7];
    }

    function daysBetweenIso(fromIso, toIso) {
        var from = Date.UTC(
            parseInt(fromIso.substring(0, 4), 10),
            parseInt(fromIso.substring(5, 7), 10) - 1,
            parseInt(fromIso.substring(8, 10), 10)
        );
        var to = Date.UTC(
            parseInt(toIso.substring(0, 4), 10),
            parseInt(toIso.substring(5, 7), 10) - 1,
            parseInt(toIso.substring(8, 10), 10)
        );
        return Math.round((to - from) / 86400000);
    }

    function localTodayIso() {
        var now = new Date();
        return now.getFullYear() + '-' + pad2(now.getMonth() + 1) + '-' + pad2(now.getDate());
    }

    // "08:00" / "8:00" / "08:00:00" → "08:00"；认不出来（含 24:00 这种越界）返回 null。
    // 写进载荷的 periodTimes 必须是 00:00-23:59 的 HH:mm，越界会让整个载荷被拒（不是跳过一节）。
    function timeOf(value) {
        var m = /^([01]?\d|2[0-3]):([0-5]\d)/.exec(text(value));
        if (!m) return null;
        return (m[1].length < 2 ? '0' + m[1] : m[1]) + ':' + m[2];
    }

    function minutesOf(hhmm) {
        return parseInt(hhmm.substring(0, 2), 10) * 60 + parseInt(hhmm.substring(3, 5), 10);
    }

    // 确定性的比较函数：不用 localeCompare（它排中文的结果跟引擎有关，而 fixture 是逐数组比对的）
    function cmpStr(a, b) {
        if (a === b) return 0;
        return a < b ? -1 : 1;
    }

    function cmpNum(a, b) {
        return a === b ? 0 : (a < b ? -1 : 1);
    }

    // ---------- 学期列表（semesterCalendar 的响应文本） ----------
    // 上游用 Function("return (...)")() 求值这段响应。这里不执行它，改成按记录取值：
    // 响应的形状是 {semesters:{"2026-2027":[{id:403,schoolYear:"2026-2027",name:"1",startDate:...}]},
    // semesterId:403}，嵌套只有一层，所以用「不含花括号的花括号块」就能切出每条学期记录。
    function valueOfKey(body, keys) {
        for (var i = 0; i < keys.length; i++) {
            var re = new RegExp(
                '(?:^|[^A-Za-z0-9_])["\']?' + keys[i] + '["\']?\\s*:\\s*(?:"([^"]*)"|\'([^\']*)\'|([^,}\\s]+))'
            );
            var m = re.exec(body);
            if (!m) continue;
            var value = m[1] !== undefined ? m[1] : (m[2] !== undefined ? m[2] : m[3]);
            if (value === undefined || value === null) continue;
            return String(value);
        }
        return '';
    }

    function semesterRecords(source) {
        var records = [];
        var seen = {};
        var re = /\{[^{}]*\}/g;
        var m;
        while ((m = re.exec(source)) !== null) {
            var body = m[0];
            var id = valueOfKey(body, ['id']);
            if (!/^\d+$/.test(id) || seen[id]) continue;
            seen[id] = true;
            records.push({
                id: id,
                schoolYear: valueOfKey(body, ['schoolYear', 'schoolYearName', 'year']),
                term: valueOfKey(body, ['name', 'term', 'semesterName']),
                startDate: isoOfAnyValue(valueOfKey(body, ['startDate', 'beginDate', 'start', 'startTime', 'dateBegin'])),
                endDate: isoOfAnyValue(valueOfKey(body, ['endDate', 'end', 'endTime', 'finishDate', 'dateEnd'])),
                at: m.index
            });
        }
        return records;
    }

    // 选学期：优先用 extract.js 交出的 semesterPick.id（它来自教务页面上标出的当前学期，或响应里的
    // semesterId）。找不到就退回「id 最大的那条」——EAMS 的学期 id 是单调递增的，最新的学期 id 最大。
    var records = semesterRecords(semesterRaw);
    var chosen = null;
    var chosenGuessed = false;
    var i;
    for (i = 0; i < records.length; i++) {
        if (records[i].id === pickedId) { chosen = records[i]; break; }
    }
    if (!chosen) {
        for (i = 0; i < records.length; i++) {
            if (!chosen || parseInt(records[i].id, 10) > parseInt(chosen.id, 10)) chosen = records[i];
        }
        chosenGuessed = chosen !== null;
    }

    var TERM_CN = { '1': '第一学期', '2': '第二学期', '3': '第三学期' };

    function termLabelOf(record) {
        var term = text(record ? record.term : '');
        if (!term) return '';
        if (/第.*学期/.test(term)) return term;
        if (TERM_CN[term]) return TERM_CN[term];
        return '第' + term + '学期';
    }

    // 学期名用教务自己的学年学期（手册 §4.7：别拿适配器名当学期名）
    function termNameOf(record) {
        var year = text(record ? record.schoolYear : '');
        var label = termLabelOf(record);
        if (year && label) return year + '学年' + label;
        if (year) return year + '学年';
        if (label) return label;
        return '当前学期';
    }

    // 学期序号：用于推算开学日的锚点（拿不到日期时）
    function termKindOf(record) {
        var term = text(record ? record.term : '');
        if (term.indexOf('二') >= 0) return 'second';
        if (term.indexOf('三') >= 0) return 'third';
        if (term.indexOf('一') >= 0) return 'first';
        if (term === '2') return 'second';
        if (term === '3') return 'third';
        return 'first';
    }

    function termYearOf(record) {
        var m = /^(\d{4})/.exec(text(record ? record.schoolYear : ''));
        return m ? parseInt(m[1], 10) : 0;
    }

    // 教务没给学期起止日期时的开学日推算。推算结果一律写进 warnings（手册 §4.2）。
    function estimateStart(record) {
        var kind = termKindOf(record);
        var year = termYearOf(record);
        var anchor = TERM_ANCHOR[kind];
        if (!year) {
            // 连学年都读不出来时退回「取数当天所在周的周一」。这条分支只有教务两个字段都不给才走到
            return { iso: mondayOnOrBeforeIso(todayIso) || todayIso, rule: '取数当天所在周的周一' };
        }
        return {
            iso: mondayOnOrBefore(year + anchor.yearOffset, anchor.month, anchor.day),
            rule: anchor.rule
        };
    }

    // ---------- 作息时间（课表表头的节次时间） ----------
    // 上游用 DOMParser 读 #manualArrangeCourseTable 每个单元格里的「第N节」+「(HH:mm-HH:mm)」。
    // 这里没有 DOMParser，改成把 HTML 按 <td / <th 切成单元格，再在单元格文本里找同样两条特征。
    // 另外认一种同族写法（ZZVCAE 用的那种）：编号在单元格自己的 id="0_N" 上、时间在文本里。
    function periodTimesFromHtml(html) {
        var source = String(html || '');
        var byNumber = {};
        var badSlots = 0;
        var cellRe = /<t[dh](\s[^>]*)?>([\s\S]*?)<\/t[dh]>/gi;
        var m;
        while ((m = cellRe.exec(source)) !== null) {
            var attrs = m[1] || '';
            var plain = String(m[2] === undefined ? '' : m[2])
                .replace(/<[^>]*>/g, ' ').replace(/&nbsp;|&#160;/gi, ' ').replace(NBSP_RE, ' ');
            var number = null;
            // 上游只认中文数字的「第N节」；这里阿拉伯数字与中文数字都认（EAMS 两种渲染都出现过）
            var labelled = /第\s*([0-9]{1,2}|[一二三四五六七八九十]+)\s*节/.exec(plain);
            if (labelled) {
                number = /^[0-9]+$/.test(labelled[1]) ? parseInt(labelled[1], 10) : cnToInt(labelled[1]);
            } else {
                var idMatch = /id\s*=\s*["']0_(\d{1,2})["']/i.exec(attrs);
                if (idMatch) number = parseInt(idMatch[1], 10);
            }
            if (!number || number < 1 || number > MAX_PERIOD) continue;
            var range = /(\d{1,2}:\d{2})\s*[-~至－]\s*(\d{1,2}:\d{2})/.exec(plain);
            if (!range) continue;
            var start = timeOf(range[1]);
            var end = timeOf(range[2]);
            if (!start || !end || minutesOf(start) >= minutesOf(end)) { badSlots++; continue; }
            if (!byNumber[number]) byNumber[number] = { periodIndex: number, start: start, end: end };
        }
        var numbers = [];
        var key;
        for (key in byNumber) {
            if (Object.prototype.hasOwnProperty.call(byNumber, key)) numbers.push(parseInt(key, 10));
        }
        numbers.sort(cmpNum);
        // 只认「从 1 开始、一个不缺」的表头：缺号说明没读全（表头被裁过、或换了别的写法），
        // 整份不用 —— 交给调用方回落空课内置作息表，并在 warnings 里说明那是内置的。
        var contiguous = numbers.length >= 2 && numbers[0] === 1;
        for (var c = 1; contiguous && c < numbers.length; c++) {
            if (numbers[c] !== numbers[c - 1] + 1) contiguous = false;
        }
        var slots = [];
        if (contiguous) {
            for (c = 0; c < numbers.length; c++) slots.push(byNumber[numbers[c]]);
        }
        return { slots: slots, badSlots: badSlots, partial: !contiguous && numbers.length > 0 };
    }

    function cnDigit(ch) {
        var table = { '一': 1, '二': 2, '三': 3, '四': 4, '五': 5, '六': 6, '七': 7, '八': 8, '九': 9 };
        return table[ch] || 0;
    }

    function cnToInt(source) {
        var s = text(source);
        if (!s) return 0;
        if (s === '十') return 10;
        var at = s.indexOf('十');
        if (at >= 0) {
            var tens = at > 0 ? cnDigit(s.charAt(0)) : 1;
            var ones = s.length > at + 1 ? cnDigit(s.charAt(at + 1)) : 0;
            return tens * 10 + ones;
        }
        return cnDigit(s);
    }

    // ---------- TaskActivity（课表 HTML 里内嵌的课程块） ----------
    // 参数位与上游同族一致，逐字保留：
    //   args[1] 教师（可能是字面量，也可能是 xxx.join(",") 表达式）
    //   args[3] 课程名   args[5] 教室   args[6] 周次位图
    function splitJsArgs(argsText) {
        var args = [];
        var current = '';
        var quote = '';
        var escaped = false;
        var i;
        for (i = 0; i < argsText.length; i++) {
            var ch = argsText.charAt(i);
            if (escaped) { current += ch; escaped = false; continue; }
            if (ch === '\\') { current += ch; escaped = true; continue; }
            if (quote) { current += ch; if (ch === quote) quote = ''; continue; }
            if (ch === '"' || ch === '\'') { current += ch; quote = ch; continue; }
            if (ch === ',') { args.push(current.trim()); current = ''; continue; }
            current += ch;
        }
        args.push(current.trim());
        // 上游在这里只 push 非空的一段，于是空参数位会让后面的参数整体前移（教师/教室/周次全部错位）。
        // 这里保留空位，只把末尾的空段剪掉。
        while (args.length && args[args.length - 1] === '') args.pop();
        return args;
    }

    // 只认 JS 字面量（单/双引号字符串、null、undefined）。不是字面量返回 null ——
    // 上游对非字面量会把整段表达式原样当值用（教师会变成 'xxx.join(",")' 这种字面文本）。
    function literalValue(token) {
        var s = String(token === null || token === undefined ? '' : token).replace(/^\s+|\s+$/g, '');
        if (!s) return null;
        if (s === 'null' || s === 'undefined') return '';
        var quote = s.charAt(0);
        if (quote !== '"' && quote !== '\'') return null;
        if (s.length < 2 || s.charAt(s.length - 1) !== quote) return null;
        // 去转义。反斜杠用 String.fromCharCode(92) 拼出来：源码里既不出现转义序列，
        // 也不出现真的控制字符（第一批有适配器把转义序列落成了真 NUL 字节，文件被 grep 当二进制看）。
        var BACKSLASH = String.fromCharCode(92);
        var inner = s.substring(1, s.length - 1);
        inner = inner.split(BACKSLASH + BACKSLASH).join(BACKSLASH);   // 转义过的反斜杠
        inner = inner.split(BACKSLASH + quote).join(quote);           // 转义过的引号
        inner = inner.split(BACKSLASH + 'n').join(' ');               // 换行 / 回车 / 制表按空白处理
        inner = inner.split(BACKSLASH + 'r').join(' ');
        inner = inner.split(BACKSLASH + 't').join(' ');
        return inner;
    }

    // 从 openIndex 处的 '(' 开始，跳过引号、按括号深度找配对的 ')'。
    // 上游的 /\(([^]*?)\)\s*;/ 遇到参数里的 ')' 会截歪。
    function readCall(source, openIndex) {
        var depth = 0;
        var quote = '';
        var escaped = false;
        var i;
        for (i = openIndex; i < source.length; i++) {
            var ch = source.charAt(i);
            if (escaped) { escaped = false; continue; }
            if (ch === '\\') { escaped = true; continue; }
            if (quote) { if (ch === quote) quote = ''; continue; }
            if (ch === '"' || ch === '\'') { quote = ch; continue; }
            if (ch === '(') { depth++; continue; }
            if (ch === ')') {
                depth--;
                if (depth === 0) return { body: source.substring(openIndex + 1, i), end: i + 1 };
            }
        }
        return { body: source.substring(openIndex + 1), end: source.length };
    }

    // 往前找 var <名字> = ["..."|{name:"..."}] 形式的数组，取最靠近课程块的那一份里的 name 值。
    // 上游（HPU / HIIT）只找 var teachers；NEUQ 找 var actTeachers；两个都认。
    function resolveNamesBefore(source, beforeIndex, varNames) {
        var from = Math.max(0, beforeIndex - RESOLVE_WINDOW);
        var segment = source.substring(from, beforeIndex);
        var bestBody = null;
        var bestAt = -1;
        var i;
        for (i = 0; i < varNames.length; i++) {
            var re = new RegExp('(?:var\\s+)?' + varNames[i] + '\\s*=\\s*\\[([\\s\\S]*?)\\]\\s*;', 'g');
            var m;
            while ((m = re.exec(segment)) !== null) {
                if (m.index >= bestAt) { bestAt = m.index; bestBody = m[1]; }
            }
        }
        if (bestBody === null) return '';
        var names = [];
        var seen = {};
        var nameRe = /name\s*:\s*(?:"([^"]*)"|'([^']*)')/g;
        var nm;
        while ((nm = nameRe.exec(bestBody)) !== null) {
            var value = text(nm[1] !== undefined ? nm[1] : nm[2]);
            if (!value || seen[value]) continue;
            seen[value] = true;
            names.push(value);
        }
        return names.join(',');
    }

    // 往前找 var <名字> = "字面量"（课程名表达式 xxx + "(1)" 的基名用得上）
    function resolveStringBefore(source, beforeIndex, varNames) {
        var from = Math.max(0, beforeIndex - RESOLVE_WINDOW);
        var segment = source.substring(from, beforeIndex);
        var best = null;
        var bestAt = -1;
        var i;
        for (i = 0; i < varNames.length; i++) {
            var re = new RegExp('(?:var\\s+)?' + varNames[i] + '\\s*=\\s*(?:"([^"]*)"|\'([^\']*)\')', 'g');
            var m;
            while ((m = re.exec(segment)) !== null) {
                if (m.index >= bestAt) {
                    bestAt = m.index;
                    best = m[1] !== undefined ? m[1] : m[2];
                }
            }
        }
        return best === null ? '' : text(best);
    }

    // 课程名收尾的括号：上游一律删，于是「高等数学A(一)」变成「高等数学A」（丢信息，用户看不出来）。
    // 这里只删「像课程号 / 教学班序号」的：括号里带数字、且没有汉字。
    function cleanCourseName(name) {
        var value = text(name);
        var stripped = value.replace(/\s*\(([^()]*)\)\s*$/, function (all, inner) {
            if (!/[0-9]/.test(inner)) return all;
            if (/[\u4e00-\u9fa5]/.test(inner)) return all;
            return '';
        });
        stripped = text(stripped);
        return stripped || value;
    }

    // 位图 → 周次数组。本批统一口径：下标 i 就是第 i 周，下标 0 是占位符。
    function weeksOfBitmap(bitmap) {
        var out = { weeks: [], zeroBit: false, clamped: 0, bad: false };
        var s = String(bitmap === null || bitmap === undefined ? '' : bitmap).replace(/\s+/g, '');
        if (!s) return out;
        if (!/^[01]+$/.test(s) || s.length < MIN_BITMAP_LENGTH) { out.bad = true; return out; }
        var i;
        for (i = 0; i < s.length; i++) {
            if (s.charAt(i) !== '1') continue;
            if (i < 1) { out.zeroBit = true; continue; }
            if (i > MAX_WEEK) { out.clamped++; continue; }
            out.weeks.push(i);
        }
        return out;
    }

    // 课表 HTML → 一组 {at, args, scope}（scope 是这个课程块之后、下一个课程块之前的文本，
    // index 赋值就在里面）。上游的边界是「下一个 activity / table0.marshalTable / 文本结尾」，
    // 这里再加一个 </script>：最后一个课程块的 scope 才不会一路吃到页面别处的 index 赋值。
    function activitiesIn(html) {
        var source = String(html || '');
        var starts = [];
        var re = /activity\s*=\s*new\s+TaskActivity\s*\(/g;
        var m;
        while ((m = re.exec(source)) !== null) {
            starts.push({ at: m.index, open: m.index + m[0].length - 1 });
        }
        var out = [];
        var i;
        for (i = 0; i < starts.length; i++) {
            var call = readCall(source, starts[i].open);
            var limit = i + 1 < starts.length ? starts[i + 1].at : source.length;
            var tail = source.substring(call.end, limit);
            var stop = tail.search(/table\d+\s*\.\s*marshalTable|<\/script>/i);
            if (stop >= 0) tail = tail.substring(0, stop);
            out.push({ at: starts[i].at, args: splitJsArgs(call.body), scope: tail });
        }
        return out;
    }

    // ---------- 上游的 mergeContiguousSections（原样移植，两处改动） ----------
    // 同一门课（名称 / 教师 / 地点 / 星期 / 周次全同）里，节次**连续**的并成一段
    // （1-2 节 + 3-4 节 → 1-4 节），完全重复的丢掉。
    // 上游的判据是「上一段的结束节 + 1 >= 本段的开始节」，于是两行**完全相同**（或本段更靠前）
    // 的重复课也会被吸进上一段；改成严格相邻（+1 ===），重复的会落在相邻段之前，被
    // 「完全重复的安排只留一条」那一步丢掉，结果一样但不会误并。
    function cmpEntry(a, b) {
        return cmpStr(a.name, b.name) || cmpStr(a.teacher, b.teacher) || cmpStr(a.position, b.position) ||
            cmpNum(a.day, b.day) || cmpStr(a.weeks.join(','), b.weeks.join(',')) ||
            cmpNum(a.startSection, b.startSection) || cmpNum(a.endSection, b.endSection);
    }

    function mergeContiguousSections(entries) {
        var list = [];
        var i;
        for (i = 0; i < entries.length; i++) {
            var item = entries[i];
            list.push({
                name: item.name,
                teacher: item.teacher,
                position: item.position,
                day: item.day,
                startSection: item.startSection,
                endSection: item.endSection,
                weeks: item.weeks.slice(0).sort(cmpNum)
            });
        }
        list.sort(cmpEntry);
        var merged = [];
        for (i = 0; i < list.length; i++) {
            var current = list[i];
            var previous = merged.length ? merged[merged.length - 1] : null;
            var canMerge = previous !== null &&
                previous.name === current.name &&
                previous.teacher === current.teacher &&
                previous.position === current.position &&
                previous.day === current.day &&
                previous.weeks.join(',') === current.weeks.join(',') &&
                previous.endSection + 1 === current.startSection;
            if (canMerge) {
                previous.endSection = Math.max(previous.endSection, current.endSection);
            } else {
                merged.push({
                    name: current.name,
                    teacher: current.teacher,
                    position: current.position,
                    day: current.day,
                    startSection: current.startSection,
                    endSection: current.endSection,
                    weeks: current.weeks.slice(0)
                });
            }
        }
        return merged;
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

    // ---------- 逐块转换 ----------
    var unitCountMatch = /\bunitCount\s*=\s*(\d{1,3})\s*;/.exec(courseHtml);
    var unitCount = DEFAULT_UNIT_COUNT;
    var unitCountRead = false;
    if (unitCountMatch) {
        var parsedUnit = parseInt(unitCountMatch[1], 10);
        if (parsedUnit >= 1 && parsedUnit <= MAX_UNIT_COUNT) {
            unitCount = parsedUnit;
            unitCountRead = true;
        }
    }

    var activities = activitiesIn(courseHtml);
    var entries = [];
    var counter = {
        shortArgs: 0,        // 参数位不足 7 个的课程块
        noName: 0,           // 课程名是空字面量
        nameExprSkipped: 0,  // 课程名是表达式且没能解析（整块跳过）
        noWeeks: 0,          // 位图里一个有效周次都没有
        badBitmap: 0,        // 位图不是 0/1 串（教务换了周次写法）
        noIndex: 0,          // 一块课一个有效节次都没解析到
        badIndex: 0,         // 解析出来的星期 / 节次越界
        zeroBit: 0,          // 位图第 0 位为 1 的课程块数
        clampedWeeks: 0,     // 超出 1..30 被丢掉的周次个数
        teacherExpr: 0,      // args[1] 是表达式且没解析出来
        roomExpr: 0,         // args[5] 是表达式且没解析出来
        nameExprResolved: 0  // 课程名是表达式但解析出来了（正常路径，不单独报警）
    };
    var a;
    for (a = 0; a < activities.length; a++) {
        var block = activities[a];
        var args = block.args;
        if (args.length < 7) { counter.shortArgs++; continue; }

        var name = literalValue(args[3]);
        if (name === null) {
            // 课程名写成 courseName + "(1)" 这种表达式：基名往前找 var courseName，后缀取末尾的字面量
            var base = resolveStringBefore(courseHtml, block.at, ['courseName', 'courseNameVar']);
            if (!base) { counter.nameExprSkipped++; continue; }
            var suffixMatch = /["']([^"']*)["']\s*$/.exec(String(args[3]));
            name = base + (suffixMatch ? suffixMatch[1] : '');
            counter.nameExprResolved++;
        }
        name = cleanCourseName(name);
        if (!name) { counter.noName++; continue; }

        // 教师：参数位为空就是「这门课没有教师」，别去借前面那一块课程的 teachers 数组
        var teacherToken = text(args[1]);
        var teacher = '';
        if (teacherToken) {
            teacher = literalValue(teacherToken);
            if (teacher === null) {
                teacher = resolveNamesBefore(courseHtml, block.at, ['teachers', 'actTeachers', 'taskTeachers']);
                if (!teacher) counter.teacherExpr++;
            }
        }

        var positionToken = text(args[5]);
        var position = '';
        if (positionToken) {
            position = literalValue(positionToken);
            if (position === null) {
                position = resolveStringBefore(courseHtml, block.at, ['roomName', 'room', 'position', 'place']);
                if (!position) counter.roomExpr++;
            }
        }
        // 教室：上游只折叠空白（NEUQ 那支还会删掉括号内容，不照抄）；这里同样只折叠空白
        position = text(position);

        var bitmapText = literalValue(args[6]);
        var weeksInfo = weeksOfBitmap(bitmapText === null ? '' : bitmapText);
        if (weeksInfo.bad) { counter.badBitmap++; continue; }
        if (weeksInfo.zeroBit) counter.zeroBit++;
        counter.clampedWeeks += weeksInfo.clamped;
        if (!weeksInfo.weeks.length) { counter.noWeeks++; continue; }

        var foundIndex = false;
        var indexRe = /index\s*=\s*(?:(\d+)\s*\*\s*unitCount\s*\+\s*(\d+)|(\d+))\s*;/g;
        indexRe.lastIndex = 0;
        var im;
        while ((im = indexRe.exec(block.scope)) !== null) {
            var linear = -1;
            if (im[1] !== undefined && im[2] !== undefined) {
                linear = parseInt(im[1], 10) * unitCount + parseInt(im[2], 10);
            } else if (im[3] !== undefined) {
                linear = parseInt(im[3], 10);
            }
            if (linear < 0) continue;
            var day = Math.floor(linear / unitCount) + 1;
            var section = (linear % unitCount) + 1;
            if (day < 1 || day > 7 || section < 1 || section > MAX_PERIOD) { counter.badIndex++; continue; }
            foundIndex = true;
            entries.push({
                name: name,
                teacher: teacher,
                position: position,
                day: day,
                startSection: section,
                endSection: section,
                weeks: weeksInfo.weeks.slice(0)
            });
        }
        if (!foundIndex) counter.noIndex++;
    }

    var merged = mergeContiguousSections(entries);

    // 课程顺序按**首次出现的课程块**（不依赖合并后的排序结果，方便人工核对）
    var order = [];
    var byCourse = {};
    var maxWeek = 0;
    var maxPeriod = 0;
    var i2;
    for (i2 = 0; i2 < entries.length; i2++) {
        var entry = entries[i2];
        var key = entry.name + SEP + entry.teacher;
        if (!byCourse[key]) {
            byCourse[key] = {
                name: entry.name,
                teacher: entry.teacher || null,
                note: null,
                blocks: [],
                seen: {}
            };
            order.push(key);
        }
    }
    for (i2 = 0; i2 < merged.length; i2++) {
        var item = merged[i2];
        var course = byCourse[item.name + SEP + item.teacher];
        if (!course) continue;
        if (item.endSection > maxPeriod) maxPeriod = item.endSection;
        var runs = runsOf(item.weeks);
        for (var r = 0; r < runs.length; r++) {
            var run = runs[r];
            var blockKey = item.day + '|' + item.startSection + '|' + item.endSection + '|' +
                run.start + '|' + run.end + '|' + run.weekType + '|' + item.position;
            if (course.seen[blockKey]) continue;   // 完全重复的安排只留一条
            course.seen[blockKey] = true;
            if (run.end > maxWeek) maxWeek = run.end;
            course.blocks.push({
                dayOfWeek: item.day,
                startPeriod: item.startSection,
                endPeriod: item.endSection,
                startWeek: run.start,
                endWeek: run.end,
                weekType: run.weekType,
                location: item.position || null
            });
        }
    }

    var courses = [];
    for (i2 = 0; i2 < order.length; i2++) {
        var built = byCourse[order[i2]];
        built.blocks.sort(function (x, y) {
            return cmpNum(x.dayOfWeek, y.dayOfWeek) || cmpNum(x.startPeriod, y.startPeriod) ||
                cmpNum(x.endPeriod, y.endPeriod) || cmpNum(x.startWeek, y.startWeek) ||
                cmpNum(x.endWeek, y.endWeek) || cmpStr(x.weekType, y.weekType) ||
                cmpStr(x.location || '', y.location || '');
        });
        courses.push({ name: built.name, teacher: built.teacher, note: null, blocks: built.blocks });
    }

    // ---------- 学期名 / 开学日 / 总周数 ----------
    var termName = termNameOf(chosen);
    var startIso = chosen ? chosen.startDate : null;
    var endIso = chosen ? chosen.endDate : null;
    var start;
    if (startIso) {
        var firstDay = mondayOnOrBeforeIso(startIso);
        start = { iso: firstDay, from: startIso, backed: firstDay !== startIso, rule: '教务的学期起止日期' };
    } else {
        start = estimateStart(chosen);
    }

    var calendarWeeks = 0;
    if (startIso && endIso) {
        var spanDays = daysBetweenIso(mondayOnOrBeforeIso(startIso), endIso);
        if (spanDays >= 0) calendarWeeks = Math.floor(spanDays / 7) + 1;
    }
    var weeksClamped = false;
    if (calendarWeeks > MAX_WEEK) { calendarWeeks = MAX_WEEK; weeksClamped = true; }

    var totalWeeks = calendarWeeks > 0 ? calendarWeeks : FALLBACK_TOTAL_WEEKS;
    var raisedBySchedule = false;
    if (maxWeek > totalWeeks) { totalWeeks = maxWeek; raisedBySchedule = true; }
    if (totalWeeks > MAX_WEEK) { totalWeeks = MAX_WEEK; weeksClamped = true; }
    if (totalWeeks < 1) totalWeeks = FALLBACK_TOTAL_WEEKS;

    // ---------- 作息时间 ----------
    var headerInfo = periodTimesFromHtml(courseHtml);
    var periodTimes = [];
    var usedBuiltinTimes = false;
    var w;
    if (headerInfo.slots.length) {
        for (w = 0; w < headerInfo.slots.length; w++) {
            periodTimes.push({
                periodIndex: headerInfo.slots[w].periodIndex,
                start: headerInfo.slots[w].start,
                end: headerInfo.slots[w].end
            });
        }
    } else {
        usedBuiltinTimes = true;
        for (w = 0; w < BUILTIN_PERIOD_TIMES.length; w++) {
            periodTimes.push({
                periodIndex: BUILTIN_PERIOD_TIMES[w].periodIndex,
                start: BUILTIN_PERIOD_TIMES[w].start,
                end: BUILTIN_PERIOD_TIMES[w].end
            });
        }
    }
    var tableLength = periodTimes.length;
    var extendedTo = 0;
    var uncoveredFrom = 0;
    for (w = tableLength + 1; w <= maxPeriod; w++) {
        var fallbackSlot = BUILTIN_PERIOD_TIMES[w - 1];
        if (fallbackSlot) {
            periodTimes.push({ periodIndex: fallbackSlot.periodIndex, start: fallbackSlot.start, end: fallbackSlot.end });
            extendedTo = w;
        } else if (!uncoveredFrom) {
            uncoveredFrom = w;
        }
    }

    if (!entries.length) {
        // 两种失败要分清楚：教务说「没课」和「给了课但我们一块都没读懂」。
        // 后者八成是接口字段变了，报「可能还没排课」会把用户和我们一起带偏。
        if (activities.length > 0) {
            throw new Error(
                '课表里有 ' + activities.length + ' 个课程块，但没有一个能解析成课程（参数位不足 ' +
                counter.shortArgs + ' 块、缺课程名 ' + (counter.noName + counter.nameExprSkipped) +
                ' 块、周次位图为空或不是 0/1 位图 ' + (counter.noWeeks + counter.badBitmap) +
                ' 块、节次一行都没解析到 ' + counter.noIndex + ' 块）：多半是教务系统改了课表页的写法，' +
                '请把这条消息反馈给我们'
            );
        }
        throw new Error(
            '这个学期没有解析到任何课程：可能还没排课（假期里常见），也可能登录状态已失效。' +
            '请重新登录、打开课表页确认能看到课表后再点「提取课表」'
        );
    }

    // ---------- warnings ----------
    // 顺序固定：算出来的、猜出来的、丢掉的都要说清楚。上限 20 条 / 每条 200 字（超了整个载荷会被拒），
    // 超出的在最后一条里如实说明，不静默截断。
    var allWarnings = [];

    function warn(message) {
        var line = String(message);
        if (line.length > MAX_WARNING_CHARS) line = line.substring(0, MAX_WARNING_CHARS - 1) + '…';
        allWarnings.push(line);
    }

    warn(
        '只导入了教务系统当前选中的学期（' + termName +
        '）；要导入别的学期，请在教务页面里切到那个学期再点「提取课表」'
    );
    if (chosenGuessed) {
        warn(
            '教务页面与学期列表都没有标出可用的当前学期（' + (pickedId ? '页面标的 id=' + pickedId + ' 不在学期列表里' : '页面没标') +
            '），已按学期列表里 id 最大的那个（' + termName + '）导入，如不对请在学期管理里改'
        );
    }

    if (start.from) {
        if (start.backed) {
            warn(
                '教务的学期起止日期从 ' + start.from + '（' + weekdayCnOf(start.from) +
                '）开始，已按「每周起始日为周一」回退到 ' + start.iso + ' 作为第 1 周开始，请在学期管理里核对'
            );
        } else {
            warn(
                '开学日期取自教务系统的学期起止日期（' + start.from + ' 起），第 1 周从 ' +
                start.iso + ' 开始，请在学期管理里核对'
            );
        }
    } else {
        warn(
            '教务系统没有给出学期起止日期，第 1 周按「' + start.rule + '」推算为 ' + start.iso +
            '，请在学期管理里核对成学校实际开学日'
        );
    }

    if (raisedBySchedule) {
        warn(
            '课表里有第 ' + maxWeek + ' 周的课，学期总周数已按 ' + totalWeeks +
            ' 周导入（否则那几周的课放不下），如与实际不符可在学期管理里改'
        );
    } else if (calendarWeeks > 0) {
        warn(
            '学期总周数取自教务系统的学期起止日期（' + calendarWeeks + ' 周）' +
            (weeksClamped ? '，已按载荷上限 ' + MAX_WEEK + ' 周截断' : '') +
            '，如与实际不符可在学期管理里改'
        );
    } else {
        warn(
            '教务系统没有给出学期起止日期，学期总周数按 ' + FALLBACK_TOTAL_WEEKS +
            ' 周计，如与实际不符可在学期管理里改'
        );
    }

    if (usedBuiltinTimes) {
        warn(
            (headerInfo.partial
                ? '课表表头只读到一部分节次时间（没有从第 1 节起读全），作息改用空课内置的 ' + tableLength + ' 节作息表（第 1 节 '
                : '课表表头没有读到节次时间，作息用空课内置的 ' + tableLength + ' 节作息表（第 1 节 ') +
            periodTimes[0].start + '-' + periodTimes[0].end + '），请在节次设置里核对成学校实际作息'
        );
    } else {
        warn(
            '作息时间取自课表表头（' + tableLength + ' 节，第 1 节 ' + periodTimes[0].start + '-' +
            periodTimes[0].end + '），如与学校实际作息不符请在节次设置里核对'
        );
    }
    if (extendedTo || uncoveredFrom) {
        var notes = [];
        if (extendedTo) {
            notes.push(
                extendedTo > tableLength + 1
                    ? '第 ' + (tableLength + 1) + '-' + extendedTo + ' 节按空课内建节次表补了时间'
                    : '第 ' + extendedTo + ' 节按空课内建节次表补了时间'
            );
        }
        if (uncoveredFrom) notes.push('第 ' + uncoveredFrom + ' 节及之后没有可用的作息时间');
        warn('课表里用到第 ' + maxPeriod + ' 节，而作息表只到第 ' + tableLength + ' 节：' + notes.join('；') + '，请核对');
    }

    if (counter.zeroBit > 0) {
        warn(
            '周次位图第 0 位为 1（' + counter.zeroBit +
            ' 处），与同族约定的占位符不符，已按忽略处理，请在导入预览里核对周次'
        );
    }
    if (!unitCountRead) {
        warn(
            '课表页里没有读到节次总数（var unitCount），已按 ' + DEFAULT_UNIT_COUNT +
            ' 节推算节次位置，如课表整体错位请反馈'
        );
    }
    if (counter.teacherExpr || counter.roomExpr) {
        warn(
            '教务脚本里有 ' + (counter.teacherExpr + counter.roomExpr) +
            ' 处课程信息写成了表达式没能解析（教师 ' + counter.teacherExpr + ' 处、教室 ' +
            counter.roomExpr + ' 处），已留空，如发现缺教师或教室请反馈'
        );
    }
    if (counter.nameExprSkipped > 0) {
        warn('有 ' + counter.nameExprSkipped + ' 门课的课程名在教务脚本里写成了表达式且没能解析，已跳过这些课，请反馈');
    }
    if (counter.badBitmap > 0) {
        warn('有 ' + counter.badBitmap + ' 处周次不是 0/1 位图（教务换了周次写法），已跳过这些课，请反馈');
    }
    if (counter.clampedWeeks > 0) {
        warn('有 ' + counter.clampedWeeks + ' 个周次超出 1-' + MAX_WEEK + ' 周，已丢弃（教务给出的周次不正常）');
    }
    if (headerInfo.badSlots > 0) {
        warn('课表表头有 ' + headerInfo.badSlots + ' 处节次时间不合法（越界或结束不晚于开始），已忽略这些节次');
    }
    var skipped = counter.shortArgs + counter.noName + counter.noWeeks + counter.noIndex + counter.badIndex;
    if (skipped > 0) {
        warn(
            '有 ' + skipped + ' 处课表数据没能解析（参数位不足 ' + counter.shortArgs + ' 处、缺课程名 ' +
            counter.noName + ' 处、周次位图为空 ' + counter.noWeeks + ' 处、节次一行都没解析到 ' +
            counter.noIndex + ' 处、星期或节次越界 ' + counter.badIndex +
            ' 处），已跳过：教务数据不完整时会出现，如发现少课请反馈'
        );
    }

    var warnings = allWarnings;
    if (allWarnings.length > MAX_WARNINGS) {
        warnings = allWarnings.slice(0, MAX_WARNINGS - 1);
        warnings.push(
            '另有 ' + (allWarnings.length - MAX_WARNINGS + 1) +
            ' 条说明因为超出上限没有显示，请把这份课表反馈给我们'
        );
    }

    return JSON.stringify({
        specVersion: 1,
        kind: 'schedule',
        ocrAssisted: false,
        warnings: warnings,
        terms: [
            {
                name: termName,
                firstDay: start.iso,
                totalWeeks: totalWeeks,
                periodTimes: periodTimes,
                courses: courses
            }
        ]
    });
})()
