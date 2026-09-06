---
name: 教务系统适配请求 / JW adapter request
about: 请求为你的学校添加教务系统课表导入适配
title: "[教务适配] <你的学校名>"
labels: jw-adapter
assignees: ''

---

**学校名称 / School name**

<!-- 例：某某大学 -->

**教务系统类型 / JW system type**

<!-- 正方 / 强智 / 青果 / 其他（截图里能看到页脚或 URL 特征）-->

**登录页 URL / Login URL**

<!-- 形如 https://jw.xxx.edu.cn/... （如不便公开可只写域名）-->

**课表页数据 / Schedule page data**（二选一，务必脱敏）

- [ ] 课表页「另存为 HTML」后附上（隐去姓名/学号）
- [ ] 浏览器控制台执行下方通用脚本，附输出 JSON（隐去姓名/学号）

```js
(function(){var rows=[];var t=document.querySelectorAll('table');
for(var i=0;i<t.length;i++){var trs=t[i].querySelectorAll('tr');
for(var j=0;j<trs.length;j++){var c=[];var td=trs[j].querySelectorAll('td,th');
for(var k=0;k<td.length;k++)c.push(td[k].innerText.replace(/\s+/g,' ').trim());
if(c.length>0)rows.push(c);}}
console.log(JSON.stringify({url:location.href,title:document.title,rows:rows}));})()
```

**截图 / Screenshots**

<!-- 课表页截图（脱敏）-->

**其他说明 / Notes**

<!-- 单双周/连堂/跨校区等特殊情况说明 -->
