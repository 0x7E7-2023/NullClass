(function () {
    // 浙江中医药大学教务适配器（正方新版 jwglxt 平台）
    // 移植自 shiguang_warehouse 的 ZCMU/zcmu.js
    //   https://github.com/XingHeYuZhuan/shiguang_warehouse  （MIT，作者 Daoguan-king）
    //   上游快照 e62554a4034386b893bcd6813c7b2b64f8c730a3（2026-09-12）
    // 上游抬头自称「基于正方教务系统 v9.0 接口适配」，实际请求的是 jwglxt 新版课表接口
    // （/jwglxt/kbcx/xskbcx_cxXsgrkb.html?gnmkdm=N2151，xnm/xqm 表单），按这个判平台。
    //
    // 移植改动：
    //   ① ES6 转 ES5（async/await 改成 then 链，去掉模板串、箭头函数、let/const）
    //   ② 上游连弹三个窗问「起始学年 / 学期 / 校区」，还要用户自己输四位年份。这里只保留
    //      「校区」一问：学年学期改成自动取教务当前选中的那一份（用户在课表页时读
    //      页面上的 #xnm / #xqm，那是他自己切过的学期），用户在课表页看得见自己选的是哪个
    //      学期，比让他敲年份靠谱。校区**必须问**——滨文 / 富春两个校区的作息时间不同，而这件事
    //      页面上没有、从排课行里也认不出来（教室名不足以下判断），只能问用户。
    //      桥不在（老版本应用 / 域名不在白名单）或用户取消时，回落滨文校区并在载荷 warnings 里说明。
    //   ③ 只取数：接口返回的排课行（kbList）原样交出去，周次解析、节次解析、课程合并、
    //      开学日推算全部挪到 parse.js（CI 会用 Rhino 真跑那一段，塞在这里的逻辑永远没有回归）
    //   ④ 上游把响应里的学生信息一起存了；这里只交出 kbList 排课行，其余字段一律不带出
    //
    // 取数方式：同源接口，两个请求，全部打在本校教务主机上：
    //   ① GET  /jwglxt/kbcx/xskbcx_cxXskbcxIndex.html?gnmkdm=N2151&layout=default
    //      读当前学年学期（页面上的 #xnm / #xqm 下拉框）。**用户已经在课表页时这一步跳过**
    //   ② POST /jwglxt/kbcx/xskbcx_cxXsgrkb.html?gnmkdm=N2151
    //      body: xnm=<4 位起始学年>&xqm=<学期代号：3=第一学期 12=第二学期>
    //      → { kbList: [ { kcmc, xm, cdmc, cdbh, xqj, jcs, zcd, ... } ] }
    // 地址都由 window.location.origin 拼出来，不出本校域，所以 manifest 的 allowHosts 是空的。
    // 登录全程由用户在 WebView 里手工完成，本脚本不读、不存、不上报任何账号信息。

    var JWLGXT = '/jwglxt';
    var GNMKDM = 'N2151';
    var INDEX_PAGE = '/kbcx/xskbcx_cxXskbcxIndex.html?gnmkdm=' + GNMKDM + '&layout=default';
    var DATA_PAGE = '/kbcx/xskbcx_cxXsgrkb.html?gnmkdm=' + GNMKDM;

    // 上游 CampusTimeSlots 里的两个校区（作息时间不同，见 parse.js 的作息表）
    var CAMPUSES = ['滨文校区', '富春校区'];

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

    function get(url) {
        return request(url, 'GET', null);
    }

    function post(url, body) {
        return request(url, 'POST', body);
    }

    // 读一个学年学期字段：正常是下拉框（跳过 value 为空的占位项，被 selected 标记的那项优先，
    // 没标记就取第一项）；万一页面把它放在隐藏 input 里，就直接读 value
    function readSelect(selectEl) {
        if (!selectEl) return null;
        var options = selectEl.options;
        if (!options && selectEl.querySelectorAll) options = selectEl.querySelectorAll('option');
        if (!options || !options.length) {
            var raw = String(selectEl.value === null || selectEl.value === undefined ? '' : selectEl.value)
                .replace(/\s+/g, '');
            return raw ? { value: raw, text: '' } : null;
        }
        var list = [];
        var picked = -1;
        var i;
        for (i = 0; i < options.length; i++) {
            var option = options[i];
            var value = String(option.value === null || option.value === undefined ? '' : option.value)
                .replace(/\s+/g, '');
            if (!value) continue;
            var label = String(option.textContent === null || option.textContent === undefined ? '' : option.textContent)
                .replace(/\s+/g, ' ').trim();
            if (option.selected && picked < 0) picked = list.length;
            list.push({ value: value, text: label });
        }
        if (!list.length) return null;
        if (picked < 0) picked = 0;
        return { value: list[picked].value, text: list[picked].text };
    }

    function readTermFromDoc(doc) {
        if (!doc || !doc.querySelector) return null;
        var year = readSelect(doc.querySelector('#xnm'));
        var season = readSelect(doc.querySelector('#xqm'));
        if (!year || !season) return null;
        return { xnm: year.value, xqm: season.value, xnmText: year.text, xqmText: season.text };
    }

    function currentTerm() {
        var onPage = readTermFromDoc(window.document);
        if (onPage) return Promise.resolve(onPage);
        if (typeof DOMParser === 'undefined') {
            throw new Error(
                '这个页面里读不到学年学期：请先在教务系统里打开「信息查询 - 个人课表查询」，再点「提取课表」'
            );
        }
        return get(jwBase() + INDEX_PAGE).then(function (html) {
            var doc = new DOMParser().parseFromString(String(html), 'text/html');
            var term = readTermFromDoc(doc);
            if (!term) {
                throw new Error(
                    '没读到学年学期：登录状态可能已失效，或教务系统改了课表页。' +
                    '请重新登录并打开「信息查询 - 个人课表查询」后再点「提取课表」'
                );
            }
            return term;
        });
    }

    // 校区：只有问用户这一条路。桥不在就返回 null，由 parse.js 兜底滨文校区并写进 warnings。
    // 用户取消（resolve null）同样按「没选」处理 —— 取消是正常结果，不该让整次提取失败。
    function askCampus() {
        if (typeof __ncCapabilities === 'undefined' || !__ncCapabilities || !__ncCapabilities.ask) {
            return Promise.resolve(null);
        }
        if (typeof __ncSelect !== 'function') return Promise.resolve(null);
        return __ncSelect({
            title: '选择校区',
            message: '滨文校区和富春校区的上课时间不一样。请选择你所在的校区：',
            items: CAMPUSES,
            defaultIndex: 0
        }).then(function (index) {
            if (typeof index !== 'number' || index < 0 || index >= CAMPUSES.length) return null;
            return CAMPUSES[index];
        }, function () {
            return null;
        });
    }

    // 课表接口（正方：POST 表单，xnm 学年 + xqm 学期代号；参数与上游逐字一致）
    function fetchKbList(term) {
        var body = 'xnm=' + encodeURIComponent(term.xnm) +
            '&xqm=' + encodeURIComponent(term.xqm) +
            '&kzlx=ck&xsdm=&kclbdm=&kclxdm=';
        return post(jwBase() + DATA_PAGE, body).then(function (text) {
            var json = null;
            try {
                json = JSON.parse(String(text));
            } catch (e) {
                json = null;
            }
            if (!json || typeof json !== 'object' || !(json.kbList instanceof Array)) {
                throw new Error(
                    '教务系统没有返回课表数据（返回的不是课表 JSON）：' +
                    '登录状态可能已失效，请重新登录后再点「提取课表」'
                );
            }
            // 只交出排课行本身；响应里夹带的学生信息（xsxx 等）一律不带出
            return json.kbList;
        });
    }

    // parse.js 推算开学日时可能要用「今天」，在脚本里读当前时间会让用例随日期失效，
    // 所以由这里取一次日期交给它
    function todayIso() {
        var now = new Date();
        var month = now.getMonth() + 1;
        var day = now.getDate();
        return now.getFullYear() + '-' + (month < 10 ? '0' : '') + month + '-' + (day < 10 ? '0' : '') + day;
    }

    function buildOutput(term, campus, rows) {
        return JSON.stringify({
            term: {
                xnm: term.xnm,
                xqm: term.xqm,
                xnmText: term.xnmText,
                xqmText: term.xqmText
            },
            campus: campus,
            today: todayIso(),
            raw: { kbList: rows }
        });
    }

    return currentTerm().then(function (term) {
        return fetchKbList(term).then(function (rows) {
            // 一行都没有时不必问校区：parse.js 会给出「这个学期没有解析到任何课程」的提示
            if (!rows.length) return buildOutput(term, null, rows);
            return askCampus().then(function (campus) {
                return buildOutput(term, campus, rows);
            });
        });
    });
})()
