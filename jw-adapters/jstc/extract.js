(function () {
    // 江苏旅游职业学院教务适配器（树维 for-std 平台，jwxt.jstc.edu.cn）
    // 移植自 shiguang_warehouse 的 JSTC/jstc_01.js
    //   https://github.com/XingHeYuZhuan/shiguang_warehouse  （MIT，作者 星河欲转）
    //   上游快照：e62554a（2026-09-12），学校名与登录地址取自同目录 adapters.yaml
    //
    // 平台说明：同一套接口在上游有四个同平台脚本可对照 —— JSTC / ZZU / CUP / CUPK
    // 都用 /student/for-std/course-table + #allSemesters + /student/ws/semester/get/{id}
    // + .../semester/{id}/print-data。我们的 ustc 适配器是**同一个平台家族的另一套取数接口**
    // （/for-std/course-table/get-data + POST .../datum，返回 lessonList/scheduleList），
    // 与本脚本的 print-data（studentTableVms[0].activities）**不同构**：所以这里只借用了
    // 周次切段与载荷装配的写法，取数与字段映射是本适配器自己的。
    //
    // 移植改动：
    //   ① 去掉「选学期」弹窗：改取用户此刻在教务页面上打开的学期（URL 里的 semester/{id}），
    //      其次取 #allSemesters 里写了 selected 属性的那一项（不看 .selected，见 optionsFromDocument）。
    //      选中的学期名与学期总数照样交给 parse.js，
    //      由它在 warnings 里如实说明「导的是哪一学期、还有几个没导」。
    //   ② 降到 ES5（Promise 链，无 async/await），去掉拾光桥（showToast / notifyTaskCompletion）。
    //   ③ 除上游读的 activities 外，多原样转交两样东西（上游都写死成常量，这里改成有就用真有）：
    //      · /student/ws/semester/get/{id} 的原始 JSON —— 里面有 startDate/endDate
    //      · print-data 里 timeTableLayout.courseUnitList —— 学校真实的作息表
    //   ④ 不做任何计算：周次切段、课程聚合、开学日期回退全在 parse.js（CI 只能跑 parse.js）。
    //
    // 请求目标（3 个 GET，全部是本校教务域 jwxt.jstc.edu.cn，见同目录 AUDIT.md）：
    //   ① GET /student/for-std/course-table                                     HTML，取 #allSemesters
    //   ② GET /student/ws/semester/get/{semesterId}                             JSON，开学/结束日期
    //   ③ GET /student/for-std/course-table/semester/{id}/print-data
    //          ?semesterId={id}&hasExperiment=true                              JSON，排课记录与作息表
    //
    // 只读课表：不读成绩/学籍/个人信息，不写页面，不外发。

    var BASE = 'https://jwxt.jstc.edu.cn';

    function text(value) {
        if (value === null || value === undefined) return '';
        return String(value).replace(/\s+/g, ' ').trim();
    }

    function snippetOf(body) {
        var snippet = String(body === null || body === undefined ? '' : body).replace(/\s+/g, ' ');
        if (snippet.length > 160) snippet = snippet.slice(0, 160) + '…';
        return snippet;
    }

    function request(url, accept) {
        return fetch(url, {
            method: 'GET',
            credentials: 'include',
            headers: {
                'Accept': accept,
                'X-Requested-With': 'XMLHttpRequest'
            }
        }).then(function (response) {
            if (response.status === 401 || response.status === 403) {
                throw new Error('教务系统拒绝访问（' + response.status + '）：请先在页面里登录，并打开「课表查询」页面');
            }
            if (response.status < 200 || response.status >= 300) {
                throw new Error('教务系统返回异常状态码：' + response.status + '（' + url + '）');
            }
            return response;
        }, function () {
            throw new Error('连不上教务系统（' + BASE + '）：请确认已经登录、并且当前打开的是教务系统的页面，再点「提取课表」');
        });
    }

    function getText(url) {
        return request(url, 'text/html,application/xhtml+xml,*/*;q=0.8').then(function (response) {
            return response.text();
        });
    }

    function getJson(url) {
        return request(url, 'application/json, */*;q=0.1').then(function (response) {
            return response.text().then(function (body) {
                try {
                    return JSON.parse(body);
                } catch (e) {
                    throw new Error('教务接口没有返回合法 JSON（' + url + '）：' + snippetOf(body));
                }
            });
        });
    }

    // 学期元数据是「有更好、没有也能跑」的那一半：拿不到就交 null，
    // 由 parse.js 推算开学日期并在 warnings 里说明（推算了什么必须让用户看见）。
    function getJsonOptional(url) {
        return getJson(url).then(null, function () {
            return null;
        });
    }

    // #allSemesters 的选项：结构化解析优先（属性顺序/引号都不挑），失败再退回正则。
    function optionsFromDocument(html) {
        var out = [];
        if (typeof DOMParser === 'undefined') return out;
        var doc = null;
        try {
            doc = new DOMParser().parseFromString(html, 'text/html');
        } catch (e) {
            return out;
        }
        if (!doc) return out;
        var select = doc.getElementById('allSemesters');
        if (!select) return out;
        var options = select.getElementsByTagName('option');
        for (var i = 0; i < options.length; i++) {
            var id = text(options[i].value);
            var label = text(options[i].textContent);
            if (!id || !label) continue;
            // 判定「页面标没标出当前学期」必须看 selected **属性**，不能看 .selected：
            // 浏览器会把「一个都没标 selected」时的第一个 option 的 .selected 置为 true，
            // 那样每个页面都会被当成「标出了当前学期」，parse.js 里那条提醒永远发不出来
            // （也让这里与下面的正则兜底（看属性）口径一致）。
            out.push({ id: id, label: label, selected: options[i].hasAttribute('selected') === true });
        }
        return out;
    }

    function optionsFromRegex(fragment) {
        var out = [];
        var regex = /<option([^>]*)>([\s\S]*?)<\/option>/gi;
        var match;
        while ((match = regex.exec(fragment)) !== null) {
            var attrs = match[1];
            var valueMatch = /value\s*=\s*["']([^"']+)["']/.exec(attrs);
            if (!valueMatch) continue;
            var id = text(valueMatch[1]);
            var label = text(match[2].replace(/<[^>]+>/g, ''));
            if (!id || !label) continue;
            out.push({
                id: id,
                label: label,
                selected: /(\s|^)selected(\s|=|$)/i.test(attrs)
            });
        }
        return out;
    }

    function semestersFromHtml(html) {
        var out = optionsFromDocument(html);
        if (out.length) return out;
        // 正则兜底只在这一个 <select> 的片段里找，别把页面里别的下拉框当成学期
        var block = /<select[^>]*id\s*=\s*["']allSemesters["'][^>]*>([\s\S]*?)<\/select>/i.exec(html);
        if (!block) return out;
        return optionsFromRegex(block[1]);
    }

    // 用户此刻打开的学期（课表页 URL 形如 .../semester/461/print...）
    function semesterIdFromLocation() {
        if (typeof window === 'undefined' || !window.location) return null;
        var path = String(window.location.pathname || '');
        var search = String(window.location.search || '');
        var m = /\/semester\/([0-9A-Za-z_-]+)/.exec(path);
        if (m) return m[1];
        var q = /[?&]semesterId=([^&#]+)/.exec(search);
        return q ? decodeURIComponent(q[1]) : null;
    }

    function findById(list, id) {
        for (var i = 0; i < list.length; i++) {
            if (String(list[i].id) === String(id)) return list[i];
        }
        return null;
    }

    // 选学期：URL 里的 > 页面上标了 selected 的 > 列表第一个。
    // 选完必须把「凭什么选的」带出去 —— 猜的那两种情况要写进 warnings。
    function pickSemester(semesters) {
        var idFromUrl = semesterIdFromLocation();
        if (idFromUrl) {
            var found = findById(semesters, idFromUrl);
            return {
                id: idFromUrl,
                label: found ? found.label : null,
                pickedBy: 'url'
            };
        }
        for (var i = 0; i < semesters.length; i++) {
            if (semesters[i].selected) {
                return { id: semesters[i].id, label: semesters[i].label, pickedBy: 'selected' };
            }
        }
        if (semesters.length) {
            return { id: semesters[0].id, label: semesters[0].label, pickedBy: 'first' };
        }
        return null;
    }

    return getText(BASE + '/student/for-std/course-table').then(function (html) {
        var semesters = semestersFromHtml(html);
        var picked = pickSemester(semesters);
        if (!picked) {
            throw new Error('没能在教务页面读到学期列表（#allSemesters）：请先打开「课表查询」页面，再点「提取课表」');
        }

        var id = encodeURIComponent(picked.id);
        var printUrl = BASE + '/student/for-std/course-table/semester/' + id +
            '/print-data?semesterId=' + id + '&hasExperiment=true';

        return Promise.all([
            getJsonOptional(BASE + '/student/ws/semester/get/' + id),
            getJson(printUrl)
        ]).then(function (results) {
            var published = results[1] || {};
            var tableVm = null;
            if (published.studentTableVms && published.studentTableVms.length) {
                tableVm = published.studentTableVms[0];
            } else if (published.studentTableVm) {
                tableVm = published.studentTableVm;
            }

            var activities = [];
            if (tableVm && tableVm.activities) activities = tableVm.activities;
            if (!activities.length && published.activities) activities = published.activities;

            var units = [];
            if (tableVm && tableVm.timeTableLayout && tableVm.timeTableLayout.courseUnitList) {
                units = tableVm.timeTableLayout.courseUnitList;
            }

            // 原样交出去：activities 与 courseUnitList 都是教务返回的对象数组，一个字段都不改
            return JSON.stringify({
                term: {
                    id: picked.id,
                    label: picked.label,
                    pickedBy: picked.pickedBy
                },
                semesters: semesters,
                semesterMeta: results[0],
                activities: activities,
                courseUnitList: units
            });
        });
    });
})()
