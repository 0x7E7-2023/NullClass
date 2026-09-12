(function () {
    // 浙江工业大学教务适配器（正方新版 jwglxt 平台）
    // 移植自 shiguang_warehouse 的 ZJUT/zjut_01.js
    //   https://github.com/XingHeYuZhuan/shiguang_warehouse  （MIT，作者 glxgo）
    //   上游快照 e62554a4034386b893bcd6813c7b2b64f8c730a3（2026-09-12）
    // 移植改动：
    //   ① ES6 → ES5（async/await 改成 then 链，去掉模板串、箭头函数、let/const）
    //   ② 不弹窗问「选哪个学年学期」，改成自动取教务当前学期：用户在课表页时读页面上的
    //      #xnm / #xqm（他自己切过的学期优先），否则取一次课表页 HTML 读同样两个下拉框的默认选中项
    //   ③ 只取数：接口返回的排课行（kbList）原样交出去，周次解析与课程合并全部挪到 parse.js
    //   ④ 不再带出学生信息（响应里的 xsxx 之类一律不带走）
    //
    // 取数方式：同源接口，最多两个请求，全部打在本校教务主机上：
    //   ① GET  /jwglxt/kbcx/xskbcx_cxXskbcxIndex.html?gnmkdm=N253508&layout=default  读当前学年学期
    //      （已经在课表页时跳过这一步）
    //   ② POST /jwglxt/kbcx/xskbcx_cxXsgrkb.html?gnmkdm=N253508                     取课表（JSON）
    // 地址都由 window.location.origin 拼出来，不出本校域，所以 manifest 的 allowHosts 是空的。
    // 登录全程由用户在 WebView 里手工完成，本脚本不读、不存、不上报任何账号信息。

    var JWLGXT = '/jwglxt';
    var GNMKDM = 'N253508';

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
    // 没标记就取第一项）；万一页面把它放在隐藏 input 里，就直接读 value（上游也是读 value 的）
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
                '这个页面里读不到学年学期：请先在教务系统里打开「信息查询 - 学生课表查询」，再点「提取课表」'
            );
        }
        return get(jwBase() + '/kbcx/xskbcx_cxXskbcxIndex.html?gnmkdm=' + GNMKDM + '&layout=default')
            .then(function (html) {
                var doc = new DOMParser().parseFromString(String(html), 'text/html');
                var term = readTermFromDoc(doc);
                if (!term) {
                    throw new Error(
                        '没读到学年学期：登录状态可能已失效，或教务系统改了课表页。' +
                        '请重新登录并打开「信息查询 - 学生课表查询」后再点「提取课表」'
                    );
                }
                return term;
            });
    }

    // 课表接口（正方：POST 表单，xnm 学年 + xqm 学期代号）
    function fetchKbList(term) {
        var body = 'xnm=' + encodeURIComponent(term.xnm) +
            '&xqm=' + encodeURIComponent(term.xqm) +
            '&kzlx=ck&xsdm=&kclbdm=&kclxdm=';
        return post(jwBase() + '/kbcx/xskbcx_cxXsgrkb.html?gnmkdm=' + GNMKDM, body)
            .then(function (text) {
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
                // 只交出排课行本身；接口响应里夹带的学生信息（xsxx 等）一律不带出
                return json.kbList;
            });
    }

    return currentTerm().then(function (term) {
        return fetchKbList(term).then(function (rows) {
            return JSON.stringify({
                term: {
                    xnm: term.xnm,
                    xqm: term.xqm,
                    xnmText: term.xnmText,
                    xqmText: term.xqmText
                },
                raw: { kbList: rows }
            });
        });
    });
})()
