(function () {
    // 马鞍山学院教务系统（强智 eams 平台，jwxt.masu.edu.cn/eams）适配器 —— 第一步：取数。
    //
    // 移植自 shiguang_warehouse 的 MASU/masu.js
    //   https://github.com/XingHeYuZhuan/shiguang_warehouse  （MIT，作者 Haooz）
    // 上游把这个脚本标成「青果/URP 金刚教务」，但学校教务处公布的地点是
    //   http://jwxt.masu.edu.cn:8080/eams/login.action
    // 路径 /eams/ 与上游解析的 #manualArrangeCourseTable + 行内「第N节」标签，是强智 eams
    // 的典型结构（同一批上游适配器里的 HPU / HIIT 也是这套结构）。这条只影响说明文字，
    // 不影响取数：课表是一张真正的 <table>，不是 canvas / 图片，所以不需要走 OCR。
    //
    // 移植改动：
    //   ① 上游把「读 DOM + 解析课程 + 算周次」揉在一个自执行脚本里；这里切成两段 ——
    //      本文件只把课表表格读成「格子 + 它在表格里的行列位置」的原始清单，不解析课程、
    //      不算周次，那些全部交给 parse.js（CI 只跑得动 parse.js）。
    //   ② 上游按单元格 id 里的线性下标算星期与节次（day = floor(n / unitCount) + 1）。
    //      这条约定在同平台的其它适配器里说法不一致，所以在 parse.js 里改成优先读表格
    //      自己的结构（每行的「第N节」标签给出节次，标签列右边的第 k 列是星期 k）；
    //      id 仍然原样带出去，节次标签读不到时才会退回上游那条算法。
    //   ③ 上游硬编码 unitCount = 11；这里从页面 JS 里读 var unitCount 原样带出去。
    //   ④ 课表是页面脚本异步画出来的，这里先等表格出现（最多 10 秒）再读。
    //   ⑤ 新增三个页面线索，交给 parse.js 用：学期名候选（<select> 当前选中项）、
    //      页面上的「第N周」选择器（用来反推开学日）、以及找不到表格时的课表图片候选。
    //
    // 本文件不发起任何网络请求（只读当前页面 DOM），manifest 的 allowHosts 为空。
    var TABLE_ID = 'manualArrangeCourseTable';
    var WAIT_MS = 10000;
    var POLL_MS = 250;
    var MAX_SEMESTER_LABELS = 10;
    var MIN_IMAGE_WIDTH = 400;
    var MIN_IMAGE_HEIGHT = 300;
    var MAX_IMAGE_DATA_LENGTH = 4000000;
    var SEMESTER_RE = /20\d{2}\s*[-—~至]\s*20\d{2}\s*学年/;
    var WEEK_OPTION_RE = /^第\s*\d{1,2}\s*周$/;

    function text(value) {
        if (value === null || value === undefined) return '';
        return String(value);
    }

    function tidy(value) {
        return text(value).replace(/\s+/g, ' ').trim();
    }

    // 课表是渲染出来的，onPageFinished 时不一定已经有了，先等一会儿
    function waitForTable() {
        return new Promise(function (resolve) {
            var deadline = new Date().getTime() + WAIT_MS;
            var tick = function () {
                var table = document.getElementById(TABLE_ID);
                if (table) {
                    resolve(table);
                    return;
                }
                if (new Date().getTime() >= deadline) {
                    resolve(null);
                    return;
                }
                setTimeout(tick, POLL_MS);
            };
            tick();
        });
    }

    // 把一个表格读成「格子 + 格子位置」。rowSpan / colSpan 会把后面的格子挤开，所以列号必须
    // 自己数（拿 td 的下标当列号，遇到合并单元格之后整行都会错位）。
    function readCells(table) {
        var occupied = {};
        var cells = [];
        var rows = table.rows || [];
        for (var r = 0; r < rows.length; r++) {
            var row = rows[r];
            var items = row.cells || [];
            var col = 0;
            for (var i = 0; i < items.length; i++) {
                var td = items[i];
                while (occupied[r + ':' + col]) col++;
                var rowSpan = td.rowSpan > 1 ? td.rowSpan : 1;
                var colSpan = td.colSpan > 1 ? td.colSpan : 1;
                for (var dr = 0; dr < rowSpan; dr++) {
                    for (var dc = 0; dc < colSpan; dc++) occupied[(r + dr) + ':' + (col + dc)] = true;
                }
                cells.push({
                    row: r,
                    col: col,
                    rowSpan: rowSpan,
                    colSpan: colSpan,
                    id: text(td.id),
                    className: text(td.className),
                    text: text(td.textContent),
                    title: tidy(td.getAttribute('title'))
                });
                col += colSpan;
            }
        }
        return cells;
    }

    // 页面 JS 里的 var unitCount = N（该校 11 节）。只在上游那条 id 算法里用得到，
    // 所以读不到也没关系，parse.js 会退回 11。
    function readUnitCount() {
        var html = document.documentElement ? text(document.documentElement.innerHTML) : '';
        var match = html.match(/\bunitCount\s*=\s*(\d{1,3})\s*;/);
        if (!match) return null;
        var count = parseInt(match[1], 10);
        return count > 0 ? count : null;
    }

    // 学期名候选：<select> 里当前选中的那一项 —— 教务自己写的学期名最准
    function readSemesterLabels() {
        var out = [];
        var selects = document.querySelectorAll('select');
        for (var i = 0; i < selects.length && out.length < MAX_SEMESTER_LABELS; i++) {
            var options = selects[i].options || [];
            var selected = options[selects[i].selectedIndex];
            if (selected && SEMESTER_RE.test(tidy(selected.text))) out.push(tidy(selected.text));
        }
        return out;
    }

    // 页面上的「第N周」选择器：整组选项都是「第N周」时才认，当前选中那项就是页面上显示的周次。
    // 教务不给开学日期，这一条能让 parse.js 反推得准一些（拿不到就退回「最近的周一」）。
    function readCurrentWeek() {
        var selects = document.querySelectorAll('select');
        for (var i = 0; i < selects.length; i++) {
            var options = selects[i].options || [];
            if (options.length < 2) continue;
            var matched = 0;
            for (var j = 0; j < options.length; j++) {
                if (WEEK_OPTION_RE.test(tidy(options[j].text))) matched++;
            }
            if (matched !== options.length) continue;
            var selected = options[selects[i].selectedIndex];
            if (!selected) continue;
            var number = tidy(selected.text).match(/\d{1,2}/);
            if (number) return parseInt(number[0], 10);
        }
        return null;
    }

    // 找不到课表表格时的兜底：把页面上最大的那张图 / 画布交出去，由 parse.js 决定是否走 OCR。
    // （这所学校实测是表格课表，这条路只在页面结构变了的时候才可能用上。）
    function pickImage() {
        var nodes = document.querySelectorAll('img, canvas');
        var best = null;
        var bestArea = 0;
        for (var i = 0; i < nodes.length; i++) {
            var el = nodes[i];
            var isCanvas = el.tagName === 'CANVAS';
            var width = isCanvas ? el.width : el.naturalWidth;
            var height = isCanvas ? el.height : el.naturalHeight;
            if (!(width >= MIN_IMAGE_WIDTH && height >= MIN_IMAGE_HEIGHT)) continue;
            if (width * height > bestArea) {
                bestArea = width * height;
                best = el;
            }
        }
        if (!best) return null;
        if (best.tagName === 'CANVAS') {
            try {
                var data = String(best.toDataURL('image/png'));
                if (data.length > MAX_IMAGE_DATA_LENGTH) return null;
                return { data: data, hint: '课表画布' };
            } catch (e) {
                return null;
            }
        }
        var src = best.currentSrc || best.src;
        return src ? { url: text(src), hint: '课表图片' } : null;
    }

    function todayIso() {
        var now = new Date();
        var pad = function (n) { return (n < 10 ? '0' : '') + n; };
        return now.getFullYear() + '-' + pad(now.getMonth() + 1) + '-' + pad(now.getDate());
    }

    return waitForTable().then(function (table) {
        var cells = table ? readCells(table) : [];
        return JSON.stringify({
            url: text(window.location ? window.location.href : ''),
            title: text(document.title),
            // 取数当天的日期。教务不给开学日期，parse.js 只能拿它当推算的基准；
            // 原样交出去（而不是让 parse.js 自己去问系统时间），回归用例才钉得住。
            today: todayIso(),
            unitCount: readUnitCount(),
            currentWeek: readCurrentWeek(),
            semesterLabels: readSemesterLabels(),
            cells: cells,
            image: cells.length ? null : pickImage()
        });
    });
})()
