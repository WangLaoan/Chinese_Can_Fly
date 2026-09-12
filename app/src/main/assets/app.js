'use strict';
const $ = (id) => document.getElementById(id);
const escapeHtml = (v) => String(v ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const icon = name => `<i data-lucide="${name}" aria-hidden="true"></i>`;
const native = typeof window.Ceyu?.send === 'function';
let state = {native, text:'', background:'', title:'', goal:'自然延续', tone:'自然', personId:'', result:null, busy:false, silent:false, paused:false, capturing:false, error:'', notice:'', feedback:'', source:'手动输入', people:[], records:[], settings:{endpoint:'https://api.deepseek.com',model:'deepseek-chat',keyConfigured:false,duration:3,handleY:.82,silentSave:false,channel:'text'}};
let page = 'session', sourceTab = 'text', mobileView = 'input', toastTimer, lastNotice = '', demo = false, modalRecord = null;
const nav = [{id:'session',label:'会话',title:'对话工作台',icon:'messages-square'},{id:'people',label:'人物',title:'人物档案',icon:'contact-round'},{id:'records',label:'记录',title:'会话记录',icon:'notebook-pen'},{id:'settings',label:'设置',title:'偏好设置',icon:'sliders-horizontal'}];
const sampleText = '我：这周末还去攀岩吗？\n对方：想去，不过这周工作有点累，可能就想躺着了。\n我：那要不要换成喝咖啡？我知道一家还不错的。\n对方：哈哈我看看，周五再说吧。';
const sampleResult = {action:'留一点余地',interpretation:'对方提到疲惫，也把决定留到周五。可以先接住这个安排，让邀请保持轻松。仅凭这几句，还不能判断对方的兴趣。',evidence:'“这周工作有点累”“周五再说吧”是明确的时间和精力线索。',uncertainty:'可能确实需要休息，也可能暂时不想答应；目前没有足够依据区分。',replies:[{style:'自然',text:'好呀，那你先好好休息，周五再看～',tradeoff:'接住对方的节奏，不继续追问。'},{style:'轻松',text:'懂了，周末充电计划优先。咖啡先留着 ☕',tradeoff:'更轻松，适合已经有一点熟悉感。'},{style:'直接',text:'没问题，你周五想出门的话叫我就好。',tradeoff:'把下一步交给对方，之后不必连续确认。'}],memoryCandidates:[{kind:'事实',text:'这周工作比较累',evidence:'对方说“这周工作有点累”。'},{kind:'推测',text:'近期可能更希望安排轻松、留有余地',evidence:'表示可能想休息，约定周五再看。'}]};
function icons(){window.lucide?.createIcons();}
function toast(message){if(!message)return; $('toast').textContent=message;$('toast').classList.add('visible');clearTimeout(toastTimer);toastTimer=setTimeout(()=>$('toast').classList.remove('visible'),3200);}
function options(values, selected){return values.map(v=>`<option value="${escapeHtml(v)}" ${v===selected?'selected':''}>${escapeHtml(v)}</option>`).join('');}
function personOptions(selected, empty='不关联已有档案'){return `<option value="">${empty}</option>`+state.people.map(p=>`<option value="${escapeHtml(p.id)}" ${p.id===selected?'selected':''}>${escapeHtml(p.name)}</option>`).join('');}
function date(value){return value?new Date(value).toLocaleString('zh-CN',{month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit'}):'';}
function dispatch(action,payload={}){
  if(native){window.Ceyu.send(action,JSON.stringify(payload));return;}
  previewAction(action,payload);
}
function draft(){return {text:$('chat-text')?.value??state.text,background:$('background')?.value??state.background,title:$('chat-title')?.value??state.title,personId:$('person-link')?.value??state.personId,goal:$('goal')?.value??state.goal,tone:$('tone')?.value??state.tone};}
function commitDraft(){const d=draft();Object.assign(state,d);if(native)dispatch('draft',d);return d;}
window.receiveState = (json) => {
  const next=typeof json==='string'?JSON.parse(json):json;
  if(next.result && next.result!==state.result && state.busy){mobileView='result';demo=false;}
  const active=document.activeElement;const preserve=active?.matches('input,textarea,select')&&!active.closest('dialog')?{id:active.id,value:active.value,start:active.selectionStart}:null;
  state=next;
  if(state.notice&&state.notice!==lastNotice){toast(state.notice);lastNotice=state.notice;}
  render();
  if(preserve&&$(preserve.id)){const el=$(preserve.id);el.value=preserve.value;el.focus();try{el.setSelectionRange(preserve.start,preserve.start);}catch{}}
};
function render(){
  const current=nav.find(n=>n.id===page);
  $('page-name').textContent=current.title;
  const markup=nav.map(n=>`<a href="#${n.id}" class="nav-item ${page===n.id?'active':''}" ${page===n.id?'aria-current="page"':''}>${icon(n.icon)}<span>${n.label}</span></a>`).join('');
  $('desktop-nav').innerHTML=markup;$('mobile-nav').innerHTML=markup;
  $('connection-label').textContent=state.settings.keyConfigured?'模型已配置':'尚未连接模型';
  document.querySelector('.connection').classList.toggle('ready',state.settings.keyConfigured);
  $('runtime-label').textContent=native?'ANDROID · 本地加密':'浏览器界面预览 · 数据仅在本次页面内';
  $('main').innerHTML=(page==='session'?sessionPage():page==='people'?peoplePage():page==='records'?recordsPage():settingsPage());
  icons();
}
function heading(title,sub,action=''){return `<div class="page-heading"><div><div class="eyebrow">${page==='session'?'CONVERSATION':page==='people'?'CONNECTIONS':page==='records'?'REFLECTIONS':'PREFERENCES'}</div><h1>${title}</h1><p class="subline">${sub}</p></div>${action}</div>`;}
function errorBlock(){return state.error?`<div class="inline-error" role="alert">${escapeHtml(state.error)}</div>`:'';}
function sessionPage(){return `${heading('对话工作台','把注意力，留给正在交流的人。',`<button class="btn" data-action="finish">${icon('square')}结束会话</button>`)}
  ${!native?'<div class="preview-tag">界面预览 · 屏幕共享、模型连接与加密档案在 Android App 中运行</div>':''}
  ${state.capturing?`<div class="session-strip"><span>${icon(state.paused?'pause':'scan-text')} 屏幕共享已开启 · ${state.paused?'分析暂停':state.silent?'建议静默':'按需读取'}</span><button class="btn small" data-action="pause">${state.paused?'恢复':'暂停'}</button></div>`:''}
  ${errorBlock()}
  <div class="mobile-switch"><button class="${mobileView==='input'?'active':''}" data-action="mobile-input">对话内容</button><button class="${mobileView==='result'?'active':''}" data-action="mobile-result">下一步建议 ${state.result?'· 1':''}</button></div>
  <div class="workspace" data-mobile="${mobileView}"><section class="work-section">
    <div class="section-bar"><h2>${icon('message-circle')}当前对话</h2><span class="badge">${icon('lock-keyhole')}由你选择上下文</span></div>
    <div class="source-tabs"><button data-action="source-text" class="${sourceTab==='text'?'active':''}">文字上下文</button><button data-action="source-screen" class="${sourceTab==='screen'?'active':''}">读取聊天屏幕</button></div>
    <div class="form-grid"><div class="field"><label for="chat-title">聊天对象${sourceTab==='screen'?' · 与窗口标题一致':''}</label><input id="chat-title" maxlength="100" value="${escapeHtml(state.title)}" placeholder="对方的昵称"></div><div class="field"><label for="person-link">关联人物</label><select id="person-link">${personOptions(state.personId)}</select></div></div>
    <div class="field"><label for="chat-text">${sourceTab==='screen'?'识别内容':'对话原文'}</label><textarea id="chat-text" class="chat-text" maxlength="14000" placeholder="我：…&#10;对方：…">${escapeHtml(state.text)}</textarea></div>
    <div class="textarea-meta"><span id="char-count">${state.text.length} / 14000</span><button data-action="sample">${demo?'正在查看示例':'载入示例会话'}</button></div>
    <div class="field context-field"><label for="background">补充背景</label><textarea id="background" maxlength="6000" rows="2" placeholder="我们的关系、之前发生的事、我想了解什么…">${escapeHtml(state.background)}</textarea></div>
    <div class="form-grid context-field"><div class="field"><label for="goal">这次想要</label><select id="goal">${options(['自然延续','理解言外之意','推进关系','修复关系','礼貌结束'],state.goal)}</select></div><div class="field"><label for="tone">表达语气</label><select id="tone">${options(['自然','真诚','幽默','直接','暧昧','克制'],state.tone)}</select></div></div>
    ${sourceTab==='screen'?`<div class="source-info">${icon('scan-line')} ${state.capturing?'共享已开启 · 切到目标聊天后点击侧边把手':'微信 / QQ · 当前可见文字 · 本地识别'}</div>`:''}
    <div class="form-actions"><span class="muted"><span class="badge ${demo?'amber':''}">${demo?'示例内容':icon('text')+'仅文字送往模型'}</span></span><div class="row">${state.busy?`<button class="btn" data-action="cancel">取消</button>`:''}<button class="btn primary" data-action="${sourceTab==='screen'?'capture':'analyze'}" ${state.busy?'disabled':''}>${icon(state.busy?'loader-circle':sourceTab==='screen'?'scan-line':'sparkles')} ${state.busy?'正在分析':sourceTab==='screen'?'开启屏幕共享':'给我建议'}</button></div></div>
  </section><section class="work-section insight"><div class="section-bar"><h2>${icon('compass')}下一步</h2><span class="badge ${demo?'amber':'green'}">${demo?'示例建议':'建议由你决定'}</span></div>${resultView(state.result)}</section></div>`;}
function resultView(r){
  if(state.silent)return `<div class="insight-empty">${icon('volume-x')}<h3>建议已静默</h3><button class="btn" data-action="silent">恢复提示</button></div>`;
  if(state.busy)return `<div class="insight-empty">${icon('loader-circle')}<h3>正在梳理这段对话</h3><p>先看原话，再考虑其他解释。</p></div>`;
  if(!r)return `<div class="insight-empty">${icon('messages-square')}<h3>等待一段对话</h3><p>一点上下文，一个更清楚的下一步。</p></div>`;
  return `<div class="next-step"><div class="label">${icon('corner-down-right')}现在可以</div><h2>${escapeHtml(r.action)}</h2><p>${escapeHtml(r.interpretation)}</p></div>
    <details class="evidence"><summary>判断依据与其他可能 ${icon('chevron-down')}</summary><p>${escapeHtml(r.evidence)}</p><p>${escapeHtml(r.uncertainty)}</p></details>
    <div class="reply-heading"><b>可以这样回应</b><span class="muted">${(r.replies||[]).length} 种表达</span></div>
    ${(r.replies||[]).map((reply,i)=>`<article class="reply"><div class="reply-top"><div class="reply-index"><span>0${i+1}</span>${escapeHtml(reply.style)}</div><button class="icon-button" data-action="copy" data-index="${i}" aria-label="复制${escapeHtml(reply.style)}回复" title="复制回复">${icon('copy')}</button></div><p>${escapeHtml(reply.text)}</p><small>${escapeHtml(reply.tradeoff)}</small></article>`).join('')}
    <div class="feedback"><span>这条建议有帮助吗？</span><div class="row"><button class="icon-button ${state.feedback==='有用'?'active':''}" data-action="helpful" title="有用" aria-label="有用">${icon('thumbs-up')}</button><button class="icon-button ${state.feedback&&state.feedback!=='有用'?'active':''}" data-action="unhelpful" title="没用" aria-label="没用">${icon('thumbs-down')}</button></div></div>`;
}
function avatar(p){return /^[A-Za-z0-9+/=]+$/.test(p.avatar||'')?`<img class="avatar" src="data:image/jpeg;base64,${escapeHtml(p.avatar)}" alt="${escapeHtml(p.name)}头像">`:`<span class="avatar">${escapeHtml((p.name||'?').slice(0,1))}</span>`;}
function peoplePage(){return `${heading('人物档案','记录你在意的人，保留可以修正的理解。')}<div class="field search"><input id="search-people" aria-label="搜索人物" placeholder="搜索昵称或笔记…"></div><div id="people-list">${peopleList('')}</div>`;}
function peopleList(query){const people=state.people.filter(p=>(p.name+' '+JSON.stringify(p.notes)).toLowerCase().includes(query.toLowerCase()));return people.length?people.map(p=>`<div class="person-row">${avatar(p)}<div class="list-text"><h3>${escapeHtml(p.name)}</h3><p>${(p.notes||[]).length} 条笔记 · ${escapeHtml(p.account||'未填写账号')}</p></div><div class="list-actions"><button class="btn small" data-action="edit-person" data-id="${escapeHtml(p.id)}">查看</button><button class="icon-button" title="删除人物及关联记录" aria-label="删除${escapeHtml(p.name)}" data-action="delete-person" data-id="${escapeHtml(p.id)}">${icon('trash-2')}</button></div></div>`).join(''):`<div class="empty-list">${icon('contact-round')}<h3>${query?'没有找到这个人':'还没有人物档案'}</h3><p>结束会话时，可以保存你认为重要的信息。</p></div>`;}
function recordsPage(){return `${heading('会话记录','回到当时的原话，重新看待你的判断。')} ${state.records.length?[...state.records].reverse().map(r=>`<div class="record-row"><span class="avatar">${icon('message-square-text')}</span><div class="list-text"><h3>${escapeHtml(r.title)}</h3><p>${date(r.at)} · ${escapeHtml(r.result?.action||'')} ${r.silent?'· 静默期间':''}</p></div><div class="list-actions"><button class="btn small" data-action="view-record" data-id="${escapeHtml(r.id)}">回看</button><button class="icon-button" title="删除记录" aria-label="删除记录" data-action="delete-record" data-id="${escapeHtml(r.id)}">${icon('trash-2')}</button></div></div>`).join(''):`<div class="empty-list">${icon('notebook-pen')}<h3>记录从一次交流开始</h3><p>你主动保存的会话会出现在这里。</p></div>`}`;}
function settingsPage(){const s=state.settings;return `${heading('偏好设置','让建议适合你的节奏。')}${errorBlock()}<div class="settings-wrap">
  <section class="settings-section"><h2>模型连接</h2><div class="field"><label for="endpoint">服务地址</label><input id="endpoint" type="url" value="${escapeHtml(s.endpoint)}" autocomplete="off" spellcheck="false"></div><div class="form-grid"><div class="field"><label for="model">模型名称</label><input id="model" value="${escapeHtml(s.model)}" spellcheck="false"></div><div class="field"><label for="api-key">API 密钥</label><input id="api-key" type="password" autocomplete="new-password" placeholder="${s.keyConfigured?'已加密保存 · 留空保持不变':'填写你的 API 密钥'}"></div></div><div class="row"><button class="btn primary" data-action="save-settings">${icon('check')}保存连接</button>${s.keyConfigured?'<button class="btn ghost" data-action="clear-key">移除密钥</button>':''}</div><p class="settings-note">文字将发送到你配置的服务。服务商的日志与留存以其政策为准。</p></section>
  <section class="settings-section"><h2>提示与悬浮窗</h2><div class="settings-row"><div><span class="row-title">提示通道</span><small>耳机断开时停止语音输出</small></div><select id="channel" aria-label="提示通道"><option value="text" ${s.channel==='text'?'selected':''}>仅文字</option><option value="ear" ${s.channel==='ear'?'selected':''}>仅耳机</option><option value="both" ${s.channel==='both'?'selected':''}>文字 + 耳机</option></select></div><div class="settings-row"><span class="row-title">文字停留 <b id="duration-value">${s.duration}</b> 秒</span><input id="duration" type="range" min="2" max="8" value="${s.duration}" aria-label="文字停留秒数"></div><div class="settings-row"><span class="row-title">把手垂直位置</span><input id="handle-y" type="range" min="10" max="90" value="${Math.round(s.handleY*100)}" aria-label="把手垂直位置"></div><div class="settings-row"><div><span class="row-title">静默期间自动保存</span><small>保存文字和结果，系统共享状态仍可见</small></div><input id="silent-save" type="checkbox" ${s.silentSave?'checked':''} aria-label="静默期间自动保存"></div><button class="btn" data-action="save-preferences">${icon('check')}保存偏好</button></section>
  <section class="settings-section"><h2>档案与备份</h2><div class="settings-row"><div><span class="row-title">本机加密</span><small>人物、头像缩略图、记录和 API 密钥</small></div><span class="badge green">${icon('lock-keyhole')}Android Keystore</span></div><div class="row"><button class="btn" data-action="export">${icon('download')}导出备份</button><button class="btn" data-action="import">${icon('upload')}导入备份</button></div><p class="settings-note">备份使用独立口令加密，不含 API 密钥。云同步尚未开放。</p></section>
  <section class="settings-section"><div class="row between"><h2>清除所有人物与记录</h2><button class="btn danger" data-action="delete-all">${icon('trash-2')}全部删除</button></div></section></div>`;}
function modal(title,body,footer=''){const d=$('modal');$('modal-content').innerHTML=`<div class="modal-head"><h2>${title}</h2><button class="icon-button" data-action="close-modal" title="关闭" aria-label="关闭">${icon('x')}</button></div><div class="modal-body">${body}</div>${footer?`<div class="modal-foot">${footer}</div>`:''}`;if(!d.open)d.showModal();icons();}
function closeModal(){$('modal').close();modalRecord=null;}
window.openFinish=()=>{
  if(demo){toast('示例内容不写入真实人物档案');return;}
  if(!state.result){modal('结束会话？','<p class="muted">本次尚未保存的文字将被清除。</p>','<button class="btn" data-action="close-modal">继续会话</button><button class="btn danger" data-action="discard">结束并清除</button>');return;}
  const candidates=state.result.memoryCandidates||[];
  modal('保存这次交流',`<div class="form-grid"><div class="field"><label for="save-name">新人物昵称（可留空）</label><input id="save-name" maxlength="100" value="${escapeHtml(state.title)}"></div><div class="field"><label for="save-person">关联已有档案</label><select id="save-person">${personOptions(state.personId,'新建或仅保存记录')}</select></div></div><p class="label">保留哪些观察</p>${candidates.map((m,i)=>`<div class="memory-choice"><input type="checkbox" id="memory-${i}" checked aria-label="保留候选${i+1}"><div class="field"><label for="memory-text-${i}">${escapeHtml(m.kind)}</label><input id="memory-text-${i}" value="${escapeHtml(m.text)}" maxlength="500"><small>${escapeHtml(m.evidence)}</small></div></div>`).join('')||'<p class="muted">这次没有需要长期保留的候选。</p>'}`,`<button class="btn ghost" data-action="discard">不保存</button><button class="btn primary" data-action="save-session">${icon('lock-keyhole')}加密保存</button>`);
};
function editPerson(id){const p=state.people.find(p=>p.id===id);if(!p)return;modal('人物档案',`<div class="row" style=""><span>${avatar(p)}</span><button class="btn small" data-action="avatar" data-id="${escapeHtml(id)}">选择头像</button></div><div class="field"><label for="person-name">昵称</label><input id="person-name" value="${escapeHtml(p.name)}" maxlength="100"></div><div class="field"><label for="person-account">账号与来源</label><input id="person-account" value="${escapeHtml(p.account)}" maxlength="200"></div><div class="field"><label for="person-notes">我的笔记 · 每行一条</label><textarea id="person-notes" rows="6">${escapeHtml((p.notes||[]).map(n=>n.text).join('\n'))}</textarea></div><p class="settings-note">${(p.history||[]).length} 次资料变更 · 编辑后的内容标为用户笔记。</p>`,`<button class="btn primary" data-action="save-person" data-id="${escapeHtml(id)}">保存修改</button>`);}
function confirmDelete(action,id,text){modal('确认删除',`<p>${escapeHtml(text)}</p>`,`<button class="btn" data-action="close-modal">取消</button><button class="btn danger" data-action="confirm-${action}" data-id="${escapeHtml(id||'')}">确认删除</button>`);}
document.addEventListener('click',e=>{
  const b=e.target.closest('[data-action]');if(!b)return;const action=b.dataset.action;
  switch(action){
    case 'settings': location.hash='settings';break;
    case 'source-text':case 'source-screen': Object.assign(state,draft());sourceTab=action==='source-text'?'text':'screen';render();break;
    case 'mobile-input':case 'mobile-result':Object.assign(state,draft());mobileView=action==='mobile-input'?'input':'result';render();break;
    case 'sample': demo=true;state.text=sampleText;state.title='林同学';state.background='活动上认识，聊过攀岩，想自然地约一次见面。';state.result=structuredClone(sampleResult);state.error='';mobileView='result';render();break;
    case 'analyze':{const d=draft();demo=false;mobileView='result';dispatch('analyze',d);break;}
    case 'capture':demo=false;dispatch('capture',draft());break;
    case 'cancel':dispatch('cancel');break;
    case 'pause':dispatch('pause');break;
    case 'silent':dispatch('silent');break;
    case 'finish':commitDraft();if(native)dispatch('finish');window.openFinish();break;
    case 'close-modal':closeModal();break;
    case 'discard':closeModal();demo=false;dispatch('emergency');dispatch('clear');mobileView='input';break;
    case 'save-session':{const candidates=(state.result?.memoryCandidates||[]).flatMap((m,i)=>$(`memory-${i}`)?.checked?[{...m,text:$(`memory-text-${i}`).value}]:[]);const p={name:$('save-name').value,personId:$('save-person').value,candidates};closeModal();dispatch('save',p);mobileView='input';break;}
    case 'copy':{const r=modalRecord?.result||state.result;dispatch('copy',{text:r.replies[+b.dataset.index].text});break;}
    case 'helpful':dispatch('feedback',{value:state.feedback==='有用'?'':'有用'});break;
    case 'unhelpful':if(state.feedback&&state.feedback!=='有用'){dispatch('feedback',{value:''});break;}modal('哪里不合适？',`<div class="stack">${['判断错了','太晚','太长','语气不合适'].map(reason=>`<button class="btn" data-action="feedback-reason" data-value="${reason}">${reason}</button>`).join('')}</div>`);break;
    case 'feedback-reason':closeModal();dispatch('feedback',{value:b.dataset.value});break;
    case 'save-settings':dispatch('settings',{endpoint:$('endpoint').value,model:$('model').value,key:$('api-key').value});$('api-key').value='';break;
    case 'clear-key':dispatch('settings',{clearKey:true});break;
    case 'save-preferences':dispatch('settings',{channel:$('channel').value,duration:+$('duration').value,handleY:+$('handle-y').value/100,silentSave:$('silent-save').checked});break;
    case 'edit-person':editPerson(b.dataset.id);break;
    case 'save-person':{const p={id:b.dataset.id,name:$('person-name').value,account:$('person-account').value,notes:$('person-notes').value};closeModal();dispatch('editPerson',p);break;}
    case 'avatar':dispatch('avatar',{id:b.dataset.id});closeModal();break;
    case 'view-record':{const r=state.records.find(r=>r.id===b.dataset.id);modalRecord=r;modal(escapeHtml(r.title),`<p class="muted">${date(r.at)} · ${r.silent?'静默期间记录':'手动保存'}</p><h3>${escapeHtml(r.result.action)}</h3><p>${escapeHtml(r.result.interpretation)}</p><details><summary>原始对话</summary><div class="record-full">${escapeHtml(r.text)}</div></details><details><summary>已保存的建议与依据</summary><div class="record-full">${escapeHtml(JSON.stringify(r.result,null,2))}</div></details>`);break;}
    case 'delete-record':confirmDelete(action,b.dataset.id,'删除这次会话记录？人物档案中已经确认的笔记会保留。');break;
    case 'delete-person':confirmDelete(action,b.dataset.id,'删除此人物、头像、笔记和所有关联会话记录？');break;
    case 'delete-all':confirmDelete(action,'','删除所有人物与记录？此操作无法撤销，模型连接设置会保留。');break;
    case 'confirm-delete-record':closeModal();dispatch('deleteRecord',{id:b.dataset.id});break;
    case 'confirm-delete-person':closeModal();dispatch('deletePerson',{id:b.dataset.id});break;
    case 'confirm-delete-all':closeModal();dispatch('deleteAll');break;
    case 'export':case 'import':modal(action==='export'?'加密导出':'导入加密备份',`<div class="field"><label for="backup-password">备份口令 · 至少 10 个字符</label><input id="backup-password" type="password" minlength="10" autocomplete="new-password"></div><p class="settings-note">口令不保存，遗失后无法恢复备份。</p>`,`<button class="btn primary" data-action="do-${action}">${action==='export'?'选择保存位置':'选择备份文件'}</button>`);break;
    case 'do-export':case 'do-import':{const password=$('backup-password').value;if(password.length<10){toast('口令至少 10 个字符');return;}closeModal();dispatch(action.slice(3),{password});break;}
  }
});
document.addEventListener('input',e=>{
  if(e.target.id==='chat-text')$('char-count').textContent=`${e.target.value.length} / 14000`;
  if(e.target.id==='duration')$('duration-value').textContent=e.target.value;
  if(e.target.id==='search-people'){$('people-list').innerHTML=peopleList(e.target.value);icons();}
});
window.addEventListener('hashchange',()=>{if(page==='session')commitDraft();page=nav.some(n=>n.id===location.hash.slice(1))?location.hash.slice(1):'session';render();window.scrollTo(0,0);});
function previewAction(action,p){
  state.error='';state.notice='';
  switch(action){
    case 'draft':Object.assign(state,p);break;
    case 'analyze':Object.assign(state,p);state.result=null;state.error='浏览器预览不发送聊天。请在 Android App 中配置模型后分析。';break;
    case 'capture':state.error='屏幕共享需要在 Android App 内启动。';break;
    case 'copy':navigator.clipboard?.writeText(p.text).then(()=>toast('已复制')).catch(()=>toast('当前浏览器未授权剪贴板'));break;
    case 'feedback':state.feedback=p.value;break;
    case 'silent':state.silent=!state.silent;break;
    case 'pause':state.paused=!state.paused;break;
    case 'cancel':state.busy=false;break;
    case 'settings':{if(p.endpoint){try{const u=new URL(p.endpoint);if(u.protocol!=='https:'||u.username||u.password)throw Error();}catch{state.error='模型地址必须是 HTTPS';break;}}const {key,...rest}=p;Object.assign(state.settings,rest);if(key)state.settings.keyConfigured=true;if(p.clearKey)state.settings.keyConfigured=false;toast('预览设置已更新，不会持久保存密钥');break;}
    case 'clear':case 'emergency':state.text='';state.background='';state.result=null;state.title='';state.personId='';state.silent=false;state.capturing=false;break;
    case 'save':case 'export':case 'import':case 'avatar':toast('加密存储与文件操作需要 Android App');break;
    case 'deleteRecord':state.records=state.records.filter(r=>r.id!==p.id);break;
    case 'deletePerson':state.people=state.people.filter(r=>r.id!==p.id);state.records=state.records.filter(r=>r.personId!==p.id);break;
    case 'deleteAll':state.people=[];state.records=[];break;
    case 'editPerson':{const person=state.people.find(x=>x.id===p.id);if(person)Object.assign(person,{name:p.name,account:p.account,notes:p.notes.split('\n').filter(Boolean).map(text=>({text,kind:'用户笔记'}))});break;}
  }
  render();
}
if(nav.some(n=>n.id===location.hash.slice(1)))page=location.hash.slice(1);
render();
