(function () {
    // 西安工业大学教务适配器（树维 EAMS 平台，jwgl2018.xatu.edu.cn/eams）—— 第一步：只取数，
    // 把教务的原始数据（含课表页全文 HTML）原样交出去。
    //
    // 移植自 shiguang_warehouse 的 XATU/myschool.js
    //   https://github.com/XingHeYuZhuan/shiguang_warehouse  （MIT，上游 maintainer 晨熯）
    //   上游快照 e62554a4034386b893bcd6813c7b2b64f8c730a3（2026-09-12）
    //   上游文件头自称「基于天津农学院适配脚本」—— 同族的 tjau / hfnu 与本件在解析那段确实是
    //   逐字克隆（同样的 powerSplit → parseTaskActivities → 位图 → idxRegex），只有主机、
    //   作息表、提示语不同。移植时按本校自己的主机与作息另外造用例，没有照抄邻件的期望值。
    //
    // 平台：树维 EAMS（上海树维信息科技有限公司 / SupWisdom，新开普子公司）。
    //   本文件写「树维」，不写「强智」—— 强智的登录路径是 /jsxsd/，与 /eams/ 不是一套。
    //
    // 移植改动：
    //   ① ES6 → ES5：上游的 async/await 改成 Promise 的 then 链（规范 §3.1 允许 Promise），
    //      去掉模板串、箭头函数、可选参数默认值、块级声明关键字与对象展开。
    //   ② 地址不再写死 http://jwgl2018.xatu.edu.cn（上游三条 fetch 全是绝对 URL）：改成
    //      当前页面的 origin + 上下文路径 /eams 拼出来。走 http 还是 https、有没有挂在门户 /
    //      网关前缀下，都由用户实际打开的那个地址决定（移植手册 §5 第 3 条）。
    //      同源相对路径的结果是 allowHosts 留空，不写任何通配。
    //   ③ 不问用户选学期（上游 showSingleSelection 弹窗）：改成自动用教务的当前学期 ——
    //      先看课表页里 semesterBar 那个元素的 value，没有就用 semesterCalendar 返回的
    //      semesterId，再没有就用列表里的最后一项。三条路都不问用户（手册 §3 第 1 步）。
    //   ④ 不解析、不算周次、不拼课程：课表 HTML 全文原样交给 parse.js（CI 里跑得到的那一段）。
    //      上游在同一段脚本里同时做取数与解析，这里按手册 §3 第 1 步切两段。
    //   ⑤ 上游的 showToast / notifyTaskCompletion / saveImportedCourses / savePresetTimeSlots
    //      这些桥调用全部没有移植；作息表也不在这里推，交给 parse.js 放进载荷。
    //   ⑥ 上游用 Function("return (" + raw + ")") 解析 semesterCalendar 的响应 —— 那是把
    //      网络取回的字符串当代码执行（手册 §5 第 6 条）。这里原样交出去，由 parse.js 用
    //      JSON.parse 解析（该校该接口就是一份 JSON）。
    //   ⑦ 学生标识 ids 是接口自己的参数（上游同款，进请求体、不出站），parse.js 不需要它，
    //      载荷里也不会出现。学号不落进任何交出去的数据。
    //
    // 取数方式：同源请求，三条，全部打在本校教务主机上（地址都由 window.location.origin 拼出来）：
    //   ① GET  /eams/courseTableForStd.action?sf_request_type=ajax          → 学号 ids + 学期栏 tagId
    //   ② POST /eams/dataQuery.action?sf_request_type=ajax                  → 学期列表（原样交出）
    //   ③ POST /eams/courseTableForStd!courseTable.action?sf_request_type=ajax → 课表页全文
    // 登录全程由用户在 WebView 里手工完成，本脚本不读、不存、不上报任何账号信息，
    // 也不碰成绩 / 学籍 / 个人信息等其它接口。

    var EAMS = '/eams';

    function originOf() {
        var loc = window.location;
        if (loc.origin) return loc.origin;
        return loc.protocol + '//' + loc.host;
    }

    // 树维的上下文路径是 /eams；万一挂在前缀下（门户 / 网关），跟着当前地址里的那一段走
    function eamsBase() {
        var path = String(window.location.pathname || '');
        var at = path.indexOf(EAMS + '/');
        if (at > 0) return originOf() + path.substring(0, at + EAMS.length);
        return originOf() + EAMS;
    }

    function detailOf(error) {
        return error && error.message ? '（' + error.message + '）' : '';
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
            throw new Error('连不上教务系统' + detailOf(error) + '：请确认已登录，再点「提取课表」');
        });
    }

    function get(url) {
        return request(url, 'GET', null);
    }

    function post(url, body) {
        return request(url, 'POST', body);
    }

    function text(value) {
        if (value === null || value === undefined) return '';
        return String(value);
    }

    // 课表入口页里有两样东西要拿：学号 ids（表单参数）与学期栏 tagId（学期列表接口要它）。
    // 正则与上游 XATU/myschool.js 一致，只是把引号放宽（有的部署用单引号）。
    function paramsFromEntry(html) {
        var source = text(html);
        var idsMatch = /bg\.form\.addInput\(\s*form\s*,\s*["']ids["']\s*,\s*["'](\d+)["']\s*\)/.exec(source);
        var tagMatch = /id=["'](semesterBar\d+Semester)["']/.exec(source);
        if (!idsMatch || !tagMatch) return null;
        var tagId = tagMatch[1];
        // 学期栏那个元素上的 value 就是教务此刻选中的学期 id；没有就交给后面的回退链
        var escaped = tagId.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
        var elementMatch = new RegExp('<[^>]*\\bid=["\']' + escaped + '["\'][^>]*>', 'i').exec(source);
        var valueMatch = elementMatch ? /\bvalue=["'](\d+)["']/i.exec(elementMatch[0]) : null;
        return {
            ids: idsMatch[1],
            tagId: tagId,
            currentSemesterId: valueMatch ? valueMatch[1] : null
        };
    }

    // 学期列表的响应是教务自己拼的一段 JS（裸对象字面量）。这里**不解析**它 ——
    // 上游用 Function("return (" + raw + ")") 求值，那是把手上的字符串当
    // 代码执行，命中移植手册 §5 第 6 条。原文原样交出去，真正的解析（只读字段、不执行任何
    // 取回来的代码）在 parse.js 里，那段在 CI 里跑得到。
    function todayIso() {
        var now = new Date();
        var pad = function (n) { return (n < 10 ? '0' : '') + n; };
        return now.getFullYear() + '-' + pad(now.getMonth() + 1) + '-' + pad(now.getDate());
    }

    return get(eamsBase() + '/courseTableForStd.action?sf_request_type=ajax').then(function (entryHtml) {
        var params = paramsFromEntry(entryHtml);
        if (!params) {
            throw new Error(
                '没能识别教务参数（学号 / 学期栏）：登录状态可能已失效，' +
                '请重新登录教务系统后再点「提取课表」'
            );
        }
        var calendarBody = 'tagId=' + encodeURIComponent(params.tagId) + '&dataType=semesterCalendar';
        return post(eamsBase() + '/dataQuery.action?sf_request_type=ajax', calendarBody)
            .then(function (semesterRaw) {
                // 交给教务的是哪个学期都没关系：currentSemesterId 为空时教务对这条请求的默认
                // 就是「当前学期」，也正是我们要的那个
                var body = 'ignoreHead=1&setting.kind=std&semester.id=' +
                    encodeURIComponent(params.currentSemesterId) + '&ids=' + encodeURIComponent(params.ids);
                return post(eamsBase() + '/courseTableForStd!courseTable.action?sf_request_type=ajax', body)
                    .then(function (courseTableHtml) {
                        return JSON.stringify({
                            url: text(window.location ? window.location.href : ''),
                            today: todayIso(),
                            // 学号是接口自己的参数（进请求体），parse.js 用不到它，也不交出去
                            tagId: params.tagId,
                            currentSemesterId: params.currentSemesterId,
                            semesterRaw: text(semesterRaw),
                            courseTableHtml: text(courseTableHtml)
                        });
                    });
            });
    });
})()
