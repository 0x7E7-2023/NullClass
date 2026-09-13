(function () {
    // 西安文理学院教务适配器（正方新版 jwglxt 平台）—— 第一步：只取数，把教务的原始排课行原样交出去。
    //
    // 移植自 shiguang_warehouse 的 XAWL/xawl_01.js
    //   https://github.com/XingHeYuZhuan/shiguang_warehouse  （MIT，作者 星河欲转）
    //   上游快照 e62554a4034386b893bcd6813c7b2b64f8c730a3（2026-09-12）
    // 上游 adaptation 说明写着「基于正方教务系统接口适配」，实际打的是
    //   POST /jwglxt/kbcx/xskbcx_cxXsgrkb.html?gnmkdm=N2151，body xnm=&xqm=
    // 与本仓库同族的 cfec 同源（都是正方新版课表查询模块 N2151），但**取数参数与周次写法
    // 按 XAWL 自己的脚本重写**，没有照抄模板的接口集合（见 AUDIT.md「取数路径」一节）。
    //
    // 移植改动：
    //   ① ES6 → ES5（async/await 改成 then 链，不使用模板串、箭头函数与块级声明关键字）
    //   ② 不再弹窗问「起始学年 + 第几学期」：先在当前课表页读学年学期下拉框（用户自己选的那个优先），
    //      读不到才按今天的日期推定；两种情况都记进 term.source，parse.js 据此在 warnings 里如实说明
    //      （移植手册 §3 第 1 步：上游的 showPrompt / showSingleSelection 一律改成自动取当前学期）
    //   ③ 不再弹窗问「夏季 / 非夏季作息」：作息表由 parse.js 按学校非夏季作息写入，
    //      夏季作息的差异写进 warnings（少一次打断，用户仍能在导入预览里看到并去学期管理改）
    //   ④ 只取数：周次解析、节次解析、拼课程全部在 parse.js（CI 里跑得到的那一段）
    //   ⑤ 只交出排课要用的 8 列；课表响应里夹带的 xh（学号）等个人信息一律不带出（规范 §8）
    //
    // 取数方式：同源相对路径，只有一次 POST，全部打在本校教务主机上：
    //   POST <当前页面所在的 /jwglxt>/kbcx/xskbcx_cxXsgrkb.html?gnmkdm=N2151
    //        body: xnm=<起始学年，四位>&xqm=<3 = 第一学期 | 12 = 第二学期>
    //   登录全程由用户在 WebView 里手工完成，本脚本不读、不存、不上报任何账号信息。

    var JWLGXT = '/jwglxt';
    var GNMKDM = 'N2151';
    var DATA_PATH = '/kbcx/xskbcx_cxXsgrkb.html?gnmkdm=' + GNMKDM;

    // 课表响应里只带走这几列 —— 上游 xawl_01.js 的 parseJsonData 读的就是它们。
    // 其余字段（尤其 xh 学号、jxbmc 教学班、xf 学分等）一律不带出载荷。
    //
    // jc 必须在这里 —— parse.js 的 sectionsOf 是「先读 jcs、读不到再读 jc」：同族部署
    // （taru / zcmu 的注释都写着「少数部署把节次写在 jc」）只给 jc 不给 jcs 时，节次全靠这条兜底。
    // 投影里漏掉 jc 的话，兜底永远拿不到值，这些部署的**每一行**都会因为「缺节次」被跳过，
    // 整个学期一门课都解析不出来。而 extract.js 不进 CI（门只跑 parse.js），这个错门看不见。
    var KEEP = ['kcmc', 'xm', 'cdmc', 'cdbh', 'xqj', 'jcs', 'jc', 'zcd'];

    // 正方学期代号：3 = 第一学期、12 = 第二学期、16 = 第三学期（夏季）。
    // 少数部署用 1 / 2 编号，一并认（上游只认 3 / 12，是在弹窗里让用户选的）。
    var SEASON_BY_CODE = { '3': '3', '12': '12', '16': '16', '1': '3', '2': '12' };

    function originOf() {
        var loc = window.location;
        if (loc.origin) return loc.origin;
        return loc.protocol + '//' + loc.host;
    }

    // 正方新版的上下文路径是 /jwglxt；万一挂在前缀下（门户 / 网关），跟着当前地址里的那一段走
    function jwBase() {
        var path = String(window.location.pathname || '');
        var at = path.indexOf(JWLGXT + '/');
        if (at > 0) return originOf() + path.substring(0, at + JWLGXT.length);
        return originOf() + JWLGXT;
    }

    function textOf(value) {
        if (value === null || value === undefined) return '';
        return String(value).replace(/\s+/g, ' ').trim();
    }

    function compact(value) {
        return textOf(value).replace(/\s+/g, '');
    }

    function jsonOf(text) {
        try {
            return JSON.parse(String(text));
        } catch (e) {
            return null;
        }
    }

    function pad2(n) {
        return (n < 10 ? '0' : '') + n;
    }

    function localTodayIso() {
        var now = new Date();
        return now.getFullYear() + '-' + pad2(now.getMonth() + 1) + '-' + pad2(now.getDate());
    }

    // ---------- 学年学期：先读页面，读不到才推定 ----------

    // 读一个下拉框：跳过 value 为空的占位项，被 selected 标记的那项优先，没标记就取第一项
    function readSelect(selectEl) {
        if (!selectEl) return null;
        var options = selectEl.options;
        if (!options && selectEl.querySelectorAll) options = selectEl.querySelectorAll('option');
        if (!options || !options.length) {
            var raw = compact(selectEl.value);
            return raw ? { value: raw, text: '' } : null;
        }
        var list = [];
        var picked = -1;
        var i;
        for (i = 0; i < options.length; i++) {
            var option = options[i];
            var value = compact(option.value);
            if (!value) continue;
            var label = textOf(option.textContent);
            if (option.selected && picked < 0) picked = list.length;
            list.push({ value: value, text: label });
        }
        if (!list.length) return null;
        if (picked < 0) picked = 0;
        return { value: list[picked].value, text: list[picked].text };
    }

    // 下拉框的 value 可能是 "2026"，也可能是 "2026-2027"；两种都取前四位
    function yearCode(value, label) {
        var fromValue = /^([0-9]{4})/.exec(compact(value));
        if (fromValue) return fromValue[1];
        var fromLabel = /^([0-9]{4})/.exec(compact(label));
        return fromLabel ? fromLabel[1] : null;
    }

    // 学期：先认代号，再认下拉框文本里的「一 / 二 / 三」
    function seasonCode(value, label) {
        var raw = compact(value);
        if (SEASON_BY_CODE[raw]) return SEASON_BY_CODE[raw];
        var whole = raw + ' ' + compact(label);
        if (whole.indexOf('二') >= 0) return '12';
        if (whole.indexOf('三') >= 0) return '16';
        if (whole.indexOf('一') >= 0) return '3';
        return null;
    }

    // 用户此刻就开在课表查询页时，读他自己选的那个学年学期（比任何推算都准）
    function readTermFromPage() {
        var doc = window.document;
        if (!doc || !doc.querySelector) return null;
        var year = readSelect(doc.querySelector('#xnm'));
        var season = readSelect(doc.querySelector('#xqm'));
        if (!year || !season) return null;
        var xnm = yearCode(year.value, year.text);
        var xqm = seasonCode(season.value, season.text);
        if (!xnm || !xqm) return null;
        return {
            xnm: xnm,
            xqm: xqm,
            xnmText: /^[0-9]{4}-[0-9]{2,4}$/.test(year.text) ? year.text : xnm + '-' + (parseInt(xnm, 10) + 1),
            xqmText: textOf(season.text),
            source: 'page'
        };
    }

    // 读不到页面就按今天的日期推定：8 月起到次年 1 月算第一学期，2-7 月算第二学期。
    // 推定值会随「今天」变，所以 parse.js 必须把它写进 warnings（规范 §4）。
    function guessTerm(now) {
        var year = now.getFullYear();
        var month = now.getMonth() + 1;
        var startYear = month >= 8 ? year : year - 1;
        var first = month >= 8 || month <= 1;
        return {
            xnm: String(startYear),
            xqm: first ? '3' : '12',
            xnmText: startYear + '-' + (startYear + 1),
            xqmText: first ? '第一学期' : '第二学期',
            source: 'guess'
        };
    }

    // ---------- 请求 ----------

    function request(url, method, body) {
        var options = {
            method: method,
            credentials: 'include',
            headers: {
                'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8',
                'X-Requested-With': 'XMLHttpRequest',
                'Accept': '*/*'
            }
        };
        if (typeof body === 'string') options.body = body;
        return fetch(url, options).then(function (response) {
            if (response.status < 200 || response.status >= 300) {
                throw new Error(
                    '教务系统返回 HTTP ' + response.status +
                    '：登录状态可能已失效，请重新登录后再点「提取课表」'
                );
            }
            return response.text();
        }, function (error) {
            var detail = error && error.message ? '（' + error.message + '）' : '';
            throw new Error('连不上教务系统' + detail + '：请确认已登录，再点「提取课表」');
        });
    }

    // 课表接口的返回是 JSON（上游按 jsonData.kbList 读）。字段名万一变了，
    // 依次认 items / rows / list，并把**实际用的是哪个键**一起交出去，parse.js 会说明。
    function pickRows(json) {
        var keys = ['kbList', 'items', 'rows', 'list'];
        for (var i = 0; i < keys.length; i++) {
            if (json[keys[i]] instanceof Array) return { key: keys[i], rows: json[keys[i]] };
        }
        return { key: '', rows: [] };
    }

    function totalOf(json) {
        var keys = ['total', 'totalResult', 'totalCount', 'count'];
        for (var i = 0; i < keys.length; i++) {
            var value = json[keys[i]];
            if (typeof value === 'number' && isFinite(value) && value >= 0) return value;
            if (typeof value === 'string' && /^[0-9]+$/.test(value)) return parseInt(value, 10);
        }
        return null;
    }

    // 只留 KEEP 里那几列（学号之类的个人信息不进载荷，也不进 fixture）
    function project(rows) {
        var out = [];
        for (var i = 0; i < rows.length; i++) {
            var row = rows[i] || {};
            var kept = {};
            for (var k = 0; k < KEEP.length; k++) {
                var name = KEEP[k];
                if (row[name] === undefined || row[name] === null) continue;
                kept[name] = row[name];
            }
            out.push(kept);
        }
        return out;
    }

    function fetchTimetable(term) {
        // 上游的 body 就是这两项（没有 cfec 那边的 kzlx / xsdm 等参数）
        var body = 'xnm=' + encodeURIComponent(term.xnm) + '&xqm=' + encodeURIComponent(term.xqm);
        return request(jwBase() + DATA_PATH, 'POST', body).then(function (raw) {
            var json = jsonOf(raw);
            if (!json || typeof json !== 'object') {
                throw new Error(
                    '教务系统没有返回课表 JSON（拿到的不是课表数据）：' +
                    '登录状态可能已失效，请重新登录后再点「提取课表」'
                );
            }
            var picked = pickRows(json);
            return JSON.stringify({
                term: {
                    xnm: term.xnm,
                    xqm: term.xqm,
                    xnmText: term.xnmText,
                    xqmText: term.xqmText,
                    source: term.source
                },
                today: localTodayIso(),
                rowsKey: picked.key,
                total: totalOf(json),
                rows: project(picked.rows)
            });
        });
    }

    var onPage = readTermFromPage();
    return fetchTimetable(onPage || guessTerm(new Date()));
})()
