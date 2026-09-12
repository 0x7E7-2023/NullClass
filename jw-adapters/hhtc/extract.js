(function () {
    // 怀化学院教务适配器 —— 强智科技「高校综合管理教务系统」（学生端 /jsxsd/）。
    // 移植自 shiguang_warehouse 的 HHTC/hhtc.js
    //   https://github.com/XingHeYuZhuan/shiguang_warehouse  （MIT，作者 星河欲转）
    //
    // 平台：与已移植的 hynu（衡阳师范学院）同平台。两份上游脚本都 POST
    //   /jsxsd/xskb/xskb_list.do，请求体一字不差（cj0701id=&zc=&demo=&xnxq01id=<学年学期>），
    //   都读 #timetable 里 div.kbcontent 的 <font title=教师|周次(节次)|教室>。
    //   取数层有三处实质差异（逐条写在 AUDIT.md 的「与 hynu 对照片」里）：
    //     ① 明细节点：hhtc 取「.kbcontent」与「.kbcontent1」两份（hynu 只取隐藏的 div.kbcontent）；
    //     ② 课名：hhtc 取「没有 title 的 <font>」，hynu 取明细开头的裸文本节点；
    //     ③ hhtc 多一层 mergeContinuousLessons()（「周 × 节次」矩阵重组连堂），落在 parse.js 里。
    //
    // 取数方式：DOM 抓取（课表页是服务端渲染的 HTML，不是 JSON）。三级兜底：
    //   ① 当前页面就有课表，且页面上的「周次」筛选为空 → 直接用（用户此刻看到的那张）
    //   ② GET  /jsxsd/xskb/xskb_list.do（教务默认的当前学期，zc 空 = 整学期）
    //   ③ POST /jsxsd/xskb/xskb_list.do（xnxq01id 取页面「学年学期」下拉框里选中的那个）
    // 这个接口一次返回整学期表格：没有分页、也没有记录总数可惦记；唯一能拿到「半张表」的
    // 入口是页面上的周次筛选（zc），所以它不为空时不用当前页面（见 AUDIT.md 第 5 条）。
    //
    // 只请求 jwmis.hhtc.edu.cn（同一台教务服务器）：不读账号密码、不写页面、不外发任何数据。
    var KB_URL = 'https://jwmis.hhtc.edu.cn/jsxsd/xskb/xskb_list.do';
    var LOGIN_HINT = '请先在页面里登录教务系统（https://jwmis.hhtc.edu.cn/），' +
        '确认能看到「课表查询」页面再点提取';

    function clean(value) {
        if (value === null || value === undefined) return '';
        return String(value).replace(/\s+/g, ' ').trim();
    }

    function pad2(n) {
        return (n < 10 ? '0' : '') + n;
    }

    // 提取时刻（ISO 日期）。只用于教务页面给不出开学日时推算第 1 周 —— 见 parse.js。
    function todayIso() {
        var now = new Date();
        return now.getFullYear() + '-' + pad2(now.getMonth() + 1) + '-' + pad2(now.getDate());
    }

    function tagOf(node) {
        return String((node && node.tagName) || '').toUpperCase();
    }

    function intAttr(node, name) {
        var raw = node && node.getAttribute ? node.getAttribute(name) : null;
        var value = parseInt(raw, 10);
        return isNaN(value) || value < 1 ? 1 : value;
    }

    // 一格里的明细。上游 hhtc 取的是 cell.querySelectorAll('.kbcontent, .kbcontent1')：
    // 同格里可能有两份明细（可见的 + 教务渲染的隐藏副本，别的强智学校实测到过）。两份都原样
    // 交出去，不在取数层判断谁是谁 —— 重复与否由 parse.js 按内容去重（见它的 dedupeParts）。
    function partsOf(cell) {
        var divs = cell.getElementsByTagName('div');
        var parts = [];
        for (var i = 0; i < divs.length; i++) {
            if (String(divs[i].className || '').indexOf('kbcontent') < 0) continue;
            var html = String(divs[i].innerHTML || '').trim();
            if (html && html !== '&nbsp;') parts.push(html);
        }
        return parts;
    }

    function cellOf(cell) {
        var text = cell.textContent !== undefined ? cell.textContent : cell.innerText;
        return { text: clean(text), parts: partsOf(cell) };
    }

    function closestTable(el) {
        var node = el;
        while (node && node.nodeType === 1) {
            if (tagOf(node) === 'TABLE') return node;
            node = node.parentNode;
        }
        return null;
    }

    // 课表容器的 id 各校不一（上游用 #timetable，其它强智学校见过 #kbtable），
    // 都没有就按「有 kbcontent 格子的那张表」找。
    function findTable(doc) {
        var byId = doc.getElementById('timetable') || doc.getElementById('kbtable');
        if (byId) return byId;
        var cell = doc.querySelector ? doc.querySelector('.kbcontent') : null;
        return cell ? closestTable(cell) : null;
    }

    // 把表格还原成二维网格。**不能**按「第几个 td 就是星期几」算：rowspan/colspan 会让
    // 格子和列错位（跨大节的课在强智里就是 rowspan），而星期正是按列号对齐的。
    // 每个格子带上它在网格里的列号 col，parse.js 只看 col；表宽（列数）也一起交出去 ——
    // parse.js 在表头没认全时要按「最后 7 列」推断星期，那时得知道整张表有多少列。
    function gridOf(table) {
        var grid = [];
        var trs = table.rows || table.getElementsByTagName('tr');
        for (var r = 0; r < trs.length; r++) {
            var row = grid[r] || (grid[r] = []);
            var col = 0;
            var kids = trs[r].children || [];
            for (var k = 0; k < kids.length; k++) {
                var node = kids[k];
                var tag = tagOf(node);
                if (tag !== 'TD' && tag !== 'TH') continue;
                while (row[col] !== undefined) col++;
                var info = cellOf(node);
                var rowSpan = intAttr(node, 'rowspan');
                var colSpan = intAttr(node, 'colspan');
                for (var dr = 0; dr < rowSpan; dr++) {
                    var target = grid[r + dr] || (grid[r + dr] = []);
                    for (var dc = 0; dc < colSpan; dc++) {
                        // parts 只落在格子的第一列：跨行（同一列、多行）继承明细 —— 那是
                        // 「一门课占多个大节」；跨列（多个星期）不继承 —— 把课算到两个星期上是错的。
                        target[col + dc] = {
                            col: col + dc,
                            text: info.text,
                            parts: dc === 0 ? info.parts : []
                        };
                    }
                }
                col += colSpan;
            }
        }
        var rows = [];
        var width = 0;
        for (var ri = 0; ri < grid.length; ri++) {
            var cells = [];
            var line = grid[ri] || [];
            var label = '';
            for (var ci = 0; ci < line.length; ci++) {
                if (line[ci] === undefined) continue;
                if (line[ci].col + 1 > width) width = line[ci].col + 1;
                if (line[ci].col === 0) label = line[ci].text;
                if (!line[ci].text && !line[ci].parts.length) continue;
                cells.push(line[ci]);
            }
            if (!cells.length) continue;
            rows.push({ label: label, cells: cells });
        }
        return { rows: rows, cols: width };
    }

    function cnNumber(value) {
        if (value === '一') return '1';
        if (value === '二') return '2';
        if (value === '三') return '3';
        return String(value);
    }

    // 兜底用的「学期名候选文字」：只从与学期有关的结构里取，不读整页 ——
    //   ① 页面标题（document.title）：强智写成「学生课表查询」这类页面名，不是个人信息；
    //   ② 页面上每个下拉框的 option 文本：选项是学期名 / 课表类型这类固定词，不是自由文本；
    //   ③ id 或 class 里带 xnxq 的元素文字（强智对「学年学期」的命名），且只在文本很短时取。
    // 取到的整串文字只在本页内存里做一次正则匹配（见 readTerm），不保留、不进载荷、不外发。
    var HINT_MAX_LENGTH = 120;

    function termTextHints(doc) {
        var hints = [];
        var title = clean(doc.title);
        if (title) hints.push(title);
        var selects = doc.getElementsByTagName('select');
        for (var i = 0; i < selects.length; i++) {
            var options = selects[i].options || [];
            for (var j = 0; j < options.length; j++) {
                var optionText = clean(options[j].text);
                if (optionText) hints.push(optionText);
            }
        }
        var labels = doc.querySelectorAll
            ? doc.querySelectorAll('[id*="xnxq"], [class*="xnxq"]')
            : [];
        for (var k = 0; k < labels.length; k++) {
            var labelText = clean(labels[k].textContent);
            if (labelText && labelText.length <= HINT_MAX_LENGTH) hints.push(labelText);
        }
        return hints;
    }

    // 学年学期：课表页顶部的下拉框（强智是 select[id|name 含 xnxq]），取「选中」的那一项 ——
    // 用户在页面上切过学期，这里就跟着他走（上游是弹窗让用户手输年份再拼 xnxq01id）。
    function readTerm(doc) {
        var code = null;
        var name = null;
        var selects = doc.getElementsByTagName('select');
        for (var i = 0; i < selects.length && !code; i++) {
            var identity = String(selects[i].id || '') + ' ' + String(selects[i].name || '');
            if (identity.indexOf('xnxq') < 0) continue;
            var options = selects[i].options || [];
            var firstCode = null;
            var firstName = null;
            for (var j = 0; j < options.length; j++) {
                var value = clean(options[j].value);
                if (!/^\d{4}-\d{4}-\d$/.test(value)) continue;
                if (firstCode === null) {
                    firstCode = value;
                    firstName = clean(options[j].text);
                }
                if (options[j].selected) {
                    code = value;
                    name = clean(options[j].text);
                }
            }
            if (!code && firstCode) {
                code = firstCode;
                name = firstName;
            }
        }
        if (!code) {
            // 下拉框认不出：在上面那几处「与学期有关」的文字里认「2026-2027学年第一学期」
            var hints = termTextHints(doc);
            for (var h = 0; h < hints.length && !code; h++) {
                var m = /(20\d{2})\s*-\s*(20\d{2})\s*学年\s*第?\s*([一二三123])\s*学期/.exec(hints[h]);
                if (!m) continue;
                code = m[1] + '-' + m[2] + '-' + cnNumber(m[3]);
                name = clean(m[0]);
            }
        }
        return { code: code, name: name };
    }

    // 页面上的「周次」筛选（强智的 zc 下拉，和 POST 里那个参数同名）。它选中具体某一周时，
    // 页面上只有那一周的课 —— 那样的表格不能当「整学期」交出去，否则用户只导入一周的课。
    function weekFilterOf(doc) {
        var selects = doc.getElementsByTagName('select');
        for (var i = 0; i < selects.length; i++) {
            var identity = String(selects[i].id || '') + ' ' + String(selects[i].name || '');
            if (identity.indexOf('zc') < 0) continue;
            var options = selects[i].options || [];
            if (!options.length) continue;
            var picked = null;
            for (var j = 0; j < options.length; j++) {
                if (options[j].selected) picked = options[j];
            }
            // 没有 selected 属性时浏览器按第一项算（强智的第一项是「全部」）
            if (!picked) picked = options[0];
            var value = clean(picked.value);
            return /^\d{1,2}$/.test(value) ? value : '';
        }
        return '';
    }

    function harvest(doc) {
        var table = findTable(doc);
        var grid = table ? gridOf(table) : { rows: [], cols: 0 };
        return {
            term: readTerm(doc),
            weekFilter: weekFilterOf(doc),
            cols: grid.cols,
            rows: grid.rows
        };
    }

    // 有课内容的格子数：判断这一页算不算「拿到了课表」
    function courseCells(rows) {
        var count = 0;
        for (var r = 0; r < rows.length; r++) {
            for (var c = 0; c < rows[r].cells.length; c++) {
                if (rows[r].cells[c].parts.length) count++;
            }
        }
        return count;
    }

    // 页面被周次筛选过就别用它（它只有那一周）；整学期要靠 GET / POST 拿
    function usable(flight) {
        return courseCells(flight.rows) > 0 && !flight.weekFilter;
    }

    function payloadOf(flight) {
        return JSON.stringify({
            now: todayIso(),
            term: flight.term,
            cols: flight.cols,
            rows: flight.rows
        });
    }

    function requestHtml(url, init) {
        return fetch(url, init).then(function (response) {
            if (response.status === 401 || response.status === 403) {
                throw new Error('教务系统拒绝访问（' + response.status + '）：' + LOGIN_HINT);
            }
            if (response.status < 200 || response.status >= 300) {
                throw new Error('教务系统返回 HTTP ' + response.status + '：' + LOGIN_HINT);
            }
            return response.text();
        }, function () {
            throw new Error('连不上教务系统：登录状态可能已失效。' + LOGIN_HINT);
        });
    }

    function htmlToDoc(html) {
        return new DOMParser().parseFromString(html, 'text/html');
    }

    // 上游的请求体，一字未改（cj0701id 是班级、zc 是周次，两个都留空 = 整学期）
    function postTerm(code) {
        return requestHtml(KB_URL, {
            method: 'POST',
            credentials: 'include',
            headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
            body: 'cj0701id=&zc=&demo=&xnxq01id=' + encodeURIComponent(code)
        });
    }

    var live = harvest(document);
    if (usable(live)) return payloadOf(live);

    return requestHtml(KB_URL, { method: 'GET', credentials: 'include' }).then(function (html) {
        var got = harvest(htmlToDoc(html));
        if (usable(got)) return payloadOf(got);
        if (!got.term.code) {
            throw new Error('教务页面里没找到课表，也没找到学年学期下拉框：' + LOGIN_HINT);
        }
        return postTerm(got.term.code).then(function (posted) {
            var again = harvest(htmlToDoc(posted));
            if (courseCells(again.rows) === 0) {
                throw new Error(
                    '教务返回的课表是空的（' + got.term.code + '）：可能本学期还没排课，' +
                    '或者登录状态已失效。' + LOGIN_HINT
                );
            }
            return payloadOf({
                term: (again.term && again.term.code) ? again.term : got.term,
                rows: again.rows
            });
        });
    });
})()
