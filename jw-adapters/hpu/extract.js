(function () {
    // 河南理工大学教务适配器（树维 EAMS 平台）—— 第一步：只取数，把教务的原始数据原样交出去。
    //
    // 移植自 shiguang_warehouse 的 HPU/hpu.js
    //   https://github.com/XingHeYuZhuan/shiguang_warehouse  （MIT，上游作者 ca1q1an）
    //   上游快照 e62554a4034386b893bcd6813c7b2b64f8c730a3（2026-09-12）
    //
    // 平台：树维 EAMS（/eams/，上海树维信息科技有限公司 / SupWisdom，新开普子公司）。
    //   上游的 adapters.yaml 写的就是「河南理工大学树维教务系统(eams架构)」，
    //   loginUrl = https://zhjw.hpu.edu.cn/eams/login.action。
    //   注意：强智走 /jsxsd/，与这一族不是一套；此前批次把 /eams/ 说成强智的地方，本批统一订正。
    //
    // 取数链（上游实测，逐字沿用它的三个请求）：
    //   ① GET  /eams/courseTableForStd.action?sf_request_type=ajax
    //        → 解析 bg.form.addInput(form,"ids","…") 拿学号、id="semesterBar…Semester" 拿学期栏 tagId
    //   ② POST /eams/dataQuery.action?sf_request_type=ajax
    //        body: tagId=<tagId>&dataType=semesterCalendar  → 学期列表（含学年、学期序号、起止日期）
    //   ③ POST /eams/courseTableForStd!courseTable.action?sf_request_type=ajax
    //        body: ignoreHead=1&setting.kind=std&startWeek=&semester.id=<id>&ids=<ids> → 课表 HTML
    //
    // 移植改动（与 parse.js 的文件头逐条对应）：
    //   ① ES6 → ES5：上游 100% async/await，这里改成 then 链；去掉模板串、箭头函数、
    //      展开运算符与块级声明关键字（Rhino 与旧 WebView 都不认）
    //   ② 地址全部由 window.location.origin + 相对路径拼出（上游本来就是这么写的），
    //      所以请求主机只有用户当前打开的那一个教务主机，manifest 的 allowHosts 留空
    //   ③ 上游弹窗让用户挑学期（showSingleSelection，还会弹「请先登录」的 toast）。
    //      这里不弹窗、不提问：直接取当前学期 —— 教务页面上标出的那个 id，其次学期列表响应里的
    //      semesterId，再不行按 id 最大的那条（EAMS 的学期 id 单调递增，最新的最大）。
    //      只导入这一个学期；要导入别的学期，用户在教务页面里切过去再点「提取课表」
    //   ④ 上游用 Function("return (" + raw + ")")() 求值学期列表的响应。这段响应来自网络，
    //      执行它正中移植手册 §5 第 6 条 —— 这里只交原文（parse.js 用定向取值读它，不执行）
    //   ⑤ 上游把课程解析、周次计算、作息解析全做在这里，再把结果推给桥。这里只交三样原始数据：
    //      学期列表响应、课表 HTML 全文、以及学号/学期 id 这些定位参数；课程、周次、作息、开学日
    //      全部由 parse.js 算（CI 里跑得到的那一段）
    //   ⑥ 上游的 showToast / notifyTaskCompletion / saveImportedCourses / savePresetTimeSlots
    //      这些推式桥调用一个都没有移植
    //   ⑦ 上游「当前页面已是渲染好的课表页 → 直接解析 DOM」那条兜底没有移植（只走接口链），
    //      理由与代价写在 AUDIT.md「已知边界」里
    //   ⑧ 不带走学生个人信息：学号只用来填接口的 ids 参数（它本来就是从该学生的课表页里读出来的），
    //      姓名、成绩、学籍一概不读
    //
    // 登录全程由用户在 WebView 里手工完成：本脚本不读、不存、不上报任何账号信息，
    // 只请求上面三条本校教务接口，全部同源。

    var EAMS = '/eams';
    var ENTRY = '/courseTableForStd.action?sf_request_type=ajax';
    var DATA_QUERY = '/dataQuery.action?sf_request_type=ajax';
    var COURSE_TABLE = '/courseTableForStd!courseTable.action?sf_request_type=ajax';

    function text(value) {
        if (value === null || value === undefined) return '';
        return String(value).replace(/\s+/g, ' ').trim();
    }

    function originOf() {
        var loc = window.location;
        if (loc.origin) return loc.origin;
        return loc.protocol + '//' + loc.host;
    }

    function pad2(n) {
        return (n < 10 ? '0' : '') + n;
    }

    function todayIso() {
        var now = new Date();
        return now.getFullYear() + '-' + pad2(now.getMonth() + 1) + '-' + pad2(now.getDate());
    }

    function request(path, method, body) {
        var options = {
            method: method,
            credentials: 'include',
            headers: {
                'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8',
                'X-Requested-With': 'XMLHttpRequest',
                'Accept': '*/*'
            }
        };
        if (typeof body === 'string') options.body = body;
        return fetch(originOf() + EAMS + path, options).then(function (response) {
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

    // ① 课表入口页：学号、学期栏的 tagId、以及页面上标出的当前学期 id
    function detectParams() {
        return request(ENTRY, 'GET', null).then(function (html) {
            var source = String(html || '');
            var idsMatch = /bg\.form\.addInput\(\s*form\s*,\s*["']ids["']\s*,\s*["'](\d+)["']\s*\)/.exec(source);
            var tagMatch = /id=["'](semesterBar\d+Semester)["']/.exec(source);
            if (!idsMatch || !tagMatch) {
                throw new Error(
                    '没能识别课表参数：登录状态可能已失效，或教务系统改了课表页。' +
                    '请在教务系统里打开「学生课表查询」确认能看到课表，再点「提取课表」'
                );
            }
            var currentSemesterId = null;
            var tagId = tagMatch[1];
            var escaped = tagId.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
            var elementMatch = new RegExp('<[^>]*\\bid=["\']' + escaped + '["\'][^>]*>', 'i').exec(source);
            if (elementMatch) {
                var valueMatch = /\bvalue=["'](\d+)["']/i.exec(elementMatch[0]);
                if (valueMatch) currentSemesterId = valueMatch[1];
            }
            return { ids: idsMatch[1], tagId: tagId, currentSemesterId: currentSemesterId };
        });
    }

    // ② 学期列表：原样把响应文本交出去（parse.js 定向读它，不执行它）
    function fetchSemesterRaw(tagId) {
        return request(DATA_QUERY, 'POST', 'tagId=' + encodeURIComponent(tagId) + '&dataType=semesterCalendar');
    }

    // 响应里标出的 semesterId（有的部署会给）
    function semesterIdInResponse(source) {
        var m = /(?:^|[^A-Za-z0-9_])semesterId\s*:\s*["']?(\d+)/.exec(String(source || ''));
        return m ? m[1] : null;
    }

    // 学期列表里 id 最大的那条（EAMS 的学期 id 单调递增，最新的学期 id 最大）
    function largestSemesterIdInResponse(source) {
        var re = /\bid\s*:\s*["']?(\d{1,12})/g;
        var best = null;
        var m;
        while ((m = re.exec(String(source || ''))) !== null) {
            if (best === null || parseInt(m[1], 10) > parseInt(best, 10)) best = m[1];
        }
        return best;
    }

    // 要导入哪个学期：教务页面标的 > 学期列表响应里的 semesterId > 列表里 id 最大的那条。
    // 三条都不成立时返回 null（调用方报错），绝不猜一个 id 去打课表接口。
    function pickSemesterId(params, semesterRaw) {
        if (params.currentSemesterId) return { id: params.currentSemesterId, source: 'page' };
        var fromResponse = semesterIdInResponse(semesterRaw);
        if (fromResponse) return { id: fromResponse, source: 'response' };
        var largest = largestSemesterIdInResponse(semesterRaw);
        if (largest) return { id: largest, source: 'largest' };
        return null;
    }

    // ③ 课表 HTML：请求体与上游逐字一致
    function fetchCourseHtml(params, semesterId) {
        var body = 'ignoreHead=1&setting.kind=std&startWeek=' +
            '&semester.id=' + encodeURIComponent(semesterId) +
            '&ids=' + encodeURIComponent(params.ids);
        return request(COURSE_TABLE, 'POST', body);
    }

    return detectParams().then(function (params) {
        return fetchSemesterRaw(params.tagId).then(function (semesterRaw) {
            var picked = pickSemesterId(params, semesterRaw);
            if (!picked) {
                throw new Error(
                    '学期列表里没有读到可用的学期：登录状态可能已失效，或教务系统改了学期接口。' +
                    '请重新登录后再点「提取课表」'
                );
            }
            return fetchCourseHtml(params, picked.id).then(function (courseHtml) {
                if (!/new\s+TaskActivity\s*\(/.test(String(courseHtml || ''))) {
                    throw new Error(
                        '课表响应里没有课程数据：可能这个学期还没排课，或登录状态已失效。' +
                        '请在教务系统里打开「学生课表查询」确认能看到课表，再点「提取课表」'
                    );
                }
                return JSON.stringify({
                    entryUrl: originOf() + EAMS + ENTRY,
                    pageUrl: text(window.location ? window.location.href : ''),
                    // 取数当天的日期：教务不给学期起止日期时 parse.js 只能拿它当推算基准，
                    // 原样交出去（而不是让 parse.js 自己去问系统时间），回归用例才钉得住
                    today: todayIso(),
                    detect: {
                        ids: params.ids,
                        tagId: params.tagId,
                        currentSemesterId: params.currentSemesterId
                    },
                    semesterPick: picked,
                    semesterRaw: semesterRaw,
                    courseHtml: courseHtml
                });
            });
        });
    });
})()
