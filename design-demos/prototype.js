const demoEntries = [
  { title: '我的大太阳', date: '9月7日', image: 'painting.jpg', line: '和妈妈在公园画画', text: '今天我和妈妈去了公园。\n我画了一个大太阳，我好开心！' },
  { title: '小叶子长大了', date: '9月6日', image: 'leaves.jpg', line: '窗台上的小发现', text: '今天我看了窗台上的小叶子。\n它又长大了一点点。' },
  { title: '一起做早餐', date: '9月5日', image: 'bread.jpg', line: '我也来帮忙', text: '今天我和家人一起准备早餐。\n面包闻起来香香的。' }
];
const originalText = '今天我和妈妈去了公园 我画了一个大太杨 我好开心';
const icons = name => (ICONS[name] || ICONS['book-open']).replace(/<svg\b/, '<svg aria-hidden="true" class="icon"');
const escapeText = value => String(value).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const tool = (action, name, title) => `<button class="icon-button" data-action="${action}" title="${title}" aria-label="${title}">${icons(name)}</button>`;
const button = (action, label, name, kind = 'primary', disabled = false) => `<button class="${kind}" data-action="${action}" ${disabled ? 'disabled' : ''}>${icons(name)}<span>${label}</span></button>`;
const photo = (file, className = 'cover') => `<img class="${className}" src="${ASSETS[file]}" alt="${file === 'painting.jpg' ? '绘画日记的演示封面' : file === 'leaves.jpg' ? '绿叶日记的演示封面' : '早餐日记的演示封面'}">`;

class DiaryPrototype {
  constructor(root, screen) {
    this.root = root;
    this.state = { screen, mode: 'voice', recording: false, seconds: 0, title: demoEntries[0].title,
      text: demoEntries[0].text, image: 'painting.jpg', originalOpen: false, editing: false,
      modal: '', month: 9, coverBusy: false, skipCover: false, saved: screen === 'read', speaking: false };
    this.token = 0;
    root.addEventListener('click', event => {
      const target = event.target.closest('[data-action]');
      if (target && !target.disabled) this.act(target.dataset.action);
    });
    root.addEventListener('input', event => {
      const field = event.target.dataset.field;
      if (field && ['title', 'text'].includes(field)) {
        this.state[field] = event.target.value;
        if (this.state.mode === 'text') root.querySelector('[data-action="to-review"]')?.toggleAttribute('disabled', !this.state.text.trim());
        root.querySelector('[data-action="save"]')?.toggleAttribute('disabled', !this.state.text.trim());
      }
    });
    this.render();
  }
  stopAudio() {
    window.speechSynthesis?.cancel();
    this.state.speaking = false;
  }
  navigate(screen) {
    clearInterval(this.timer);
    this.state.recording = false;
    this.stopAudio();
    this.state.screen = screen;
    this.state.modal = '';
    this.render();
  }
  toast(message) {
    this.root.querySelector('.toast')?.remove();
    const node = document.createElement('div');
    node.className = 'toast'; node.setAttribute('role', 'status'); node.textContent = message;
    this.root.append(node);
    setTimeout(() => node.remove(), 3200);
  }
  async act(action) {
    const s = this.state;
    if (action.startsWith('entry-')) {
      const entry = demoEntries[Number(action.slice(6))];
      Object.assign(s, { title: entry.title, text: entry.text, image: entry.image, coverBusy: false, skipCover: false, saved: true });
      this.navigate('read'); return;
    }
    switch (action) {
      case 'home': this.navigate('home'); break;
      case 'record': s.mode = 'voice'; s.seconds = 0; this.navigate('record'); break;
      case 'type': s.mode = 'text'; this.navigate('record'); break;
      case 'voice': s.mode = 'voice'; this.render(); break;
      case 'mic':
        if (s.recording) {
          clearInterval(this.timer); s.recording = false; s.editing = false; this.navigate('review');
        } else {
          s.recording = true; s.seconds = 0; this.render();
          this.timer = setInterval(() => {
            s.seconds++;
            const timer = this.root.querySelector('.timer');
            if (timer) timer.textContent = `00:${String(s.seconds).padStart(2, '0')}`;
          }, 1000);
        }
        break;
      case 'to-review': if (s.text.trim()) { s.editing = false; this.navigate('review'); } break;
      case 'original': s.originalOpen = !s.originalOpen; this.render(); break;
      case 'edit': s.editing = !s.editing; this.render(); break;
      case 'edit-read': s.editing = true; this.navigate('review'); break;
      case 'use-original': s.text = originalText; s.originalOpen = false; s.editing = true; this.render(); break;
      case 'listen':
        if (s.speaking) { this.stopAudio(); this.render(); break; }
        if (!('speechSynthesis' in window)) { this.toast('这个浏览器暂时不能朗读'); break; }
        s.speaking = true; this.render();
        const utterance = new SpeechSynthesisUtterance(s.text);
        utterance.lang = 'zh-CN'; utterance.rate = .8;
        const voice = speechSynthesis.getVoices().find(v => v.lang.startsWith('zh'));
        if (voice) utterance.voice = voice;
        const end = () => { s.speaking = false; this.render(); };
        utterance.onend = end;
        utterance.onerror = () => { end(); this.toast('暂时没有可用的中文朗读声音'); };
        speechSynthesis.cancel(); speechSynthesis.speak(utterance); break;
      case 'save':
        if (!s.text.trim()) { this.toast('先写一点，再收起来'); break; }
        s.saved = true; s.coverBusy = true; s.skipCover = false;
        this.navigate('read'); this.finishCover(); break;
      case 'skip-cover': this.token++; s.coverBusy = false; s.skipCover = true; this.render(); break;
      case 'redo-cover': s.coverBusy = true; s.skipCover = false; this.render(); this.finishCover(); break;
      case 'calendar': s.modal = 'calendar'; this.render(); break;
      case 'month-8': s.month = 8; s.modal = ''; this.render(); break;
      case 'month-9': s.month = 9; s.modal = ''; this.render(); break;
      case 'settings': s.modal = 'gate'; this.render(); break;
      case 'gate':
        if (this.root.querySelector('[data-gate]').value.trim() === '23') { s.modal = 'settings'; this.render(); }
        else this.toast('再算一算：16 + 7'); break;
      case 'close-modal': s.modal = ''; this.render(); break;
      case 'save-settings': s.modal = ''; this.render(); this.toast('本次预览设置已保存'); break;
      case 'save-draft': this.navigate('home'); this.toast('这段话已留在草稿里'); break;
    }
  }
  finishCover() {
    const token = ++this.token;
    setTimeout(() => {
      if (this.token !== token) return;
      this.state.coverBusy = false;
      this.state.image = 'painting.jpg';
      this.render();
    }, 1500);
  }
  head(title = '') {
    return title ? `<header class="app-head">${tool('home', 'arrow-left', '返回日记')}<span class="head-title">${title}</span>${tool('settings', 'settings', '家长设置')}</header>` :
      `<header class="app-head"><div class="brand">${icons('book-open')}<strong>小小日记</strong></div>${tool('settings', 'settings', '家长设置')}</header>`;
  }
  nav() {
    return `<nav class="bottom-nav" aria-label="主要导航"><button class="${this.state.screen === 'home' ? 'active' : ''}" data-action="home">${icons('book-open')}我的日记</button><button class="${this.state.screen === 'record' ? 'active' : ''}" data-action="record">${icons('mic')}说一篇</button></nav>`;
  }
  entry(index = 0, mini = false) {
    const entry = demoEntries[index];
    return mini ? `<button class="entry-mini" data-action="entry-${index}">${photo(entry.image)}<span><strong>${entry.title}</strong><small>${entry.date} · ${entry.line}</small></span></button>` :
      `<button class="entry" data-action="entry-${index}"><div class="image-wrap">${photo(entry.image)}<span class="entry-date">${entry.date}</span></div><div class="entry-copy"><h3>${entry.title}</h3><p>${entry.line}</p></div></button>`;
  }
  home() {
    if (this.state.month !== 9) return this.head() + `<main class="content"><div class="dayline"><h2>8 月的日记</h2>${tool('calendar','calendar-days','选择月份')}</div><div class="empty-state">这个月还没有日记</div>${button('month-9','回到 9 月','arrow-left','secondary')}</main>` + this.nav();
    if (VARIANT === 'gallery') return this.head() + `<main class="content"><div class="gallery-intro"><div><div class="eyebrow">每一天，都值得记住</div><h2>我的故事</h2></div><button class="month-mark" data-action="calendar" aria-label="选择月份">09</button></div><div class="home-grid">${this.entry(0)}<div class="entries"><p class="section-label">还有这些小日子</p>${this.entry(1,true)}${this.entry(2,true)}</div></div></main><footer class="action-bar">${button('record','说说今天','mic')}${button('type','写下来','keyboard','text-action')}</footer>`;
    if (VARIANT === 'studio') return this.head() + `<main class="content"><div class="studio-home"><section class="studio-top"><h2>今天，想说点什么？</h2><p>开心的、难过的，都可以。</p><button class="studio-start" data-action="record">${icons('mic')}说说今天</button><div class="studio-ways"><button data-action="type">${icons('keyboard')}写下来</button><button data-action="entry-0">${icons('book-open')}看一篇</button></div></section><section><div class="studio-recent"><h3>最近的小故事</h3>${tool('calendar','calendar-days','选择月份')}</div><div class="studio-collection">${this.entry(0)}<div>${this.entry(1,true)}${this.entry(2,true)}</div></div><p class="studio-quote">你的故事，用你的声音。</p></section></div></main>` + this.nav();
    return this.head() + `<main class="content book-home"><div class="book-date">2026 年 9 月${tool('calendar','calendar-days','选择月份')}</div><h2 class="book-title">把今天，收藏起来。</h2><div class="book-main"><button class="entry book-open" data-action="entry-0"><div class="book-spine"><span>我的小小日记</span><small>09</small></div><div><div class="image-wrap">${photo('painting.jpg')}</div><div class="entry-copy"><div class="book-ribbon">${icons('volume-2')}9月7日的小故事</div><h3>我的大太阳</h3><p>“我画了一个大太阳，<br>我好开心！”</p></div></div></button><section class="book-recent"><p class="section-label">翻翻前几页</p>${this.entry(1,true)}${this.entry(2,true)}</section></div></main><footer class="action-bar">${button('record','添一页今天','mic')}${button('type','用文字记录','pencil','text-action')}</footer>`;
  }
  steps(current) {
    return VARIANT === 'studio' ? `<div class="step-strip" aria-label="日记步骤">${['说一说','听一听','收起来'].map((v,i)=>`<span class="${i===current?'current':''}">${i+1} · ${v}</span>`).join('')}</div>` : '';
  }
  record() {
    const s = this.state;
    const title = s.recording ? '我在听，你慢慢说' : VARIANT === 'book' ? '今天的小故事' : '把今天说给我听';
    const modes = `<div class="mode-switch" role="group" aria-label="输入方式"><button data-action="voice" class="${s.mode==='voice'?'selected':''}">${icons('mic')}说一说</button><button data-action="type" class="${s.mode==='text'?'selected':''}">${icons('keyboard')}写下来</button></div>`;
    let inner = s.mode === 'text' ? `<label for="text-${this.root.id}">今天发生了什么？</label><textarea id="text-${this.root.id}" data-field="text" maxlength="12000">${escapeText(s.text)}</textarea>` :
      `<section class="record-layout ${s.recording?'is-recording':''}"><h2>${title}</h2><p class="record-sub">${s.recording?'想到哪里，就说到哪里。':'一件小事，也可以是一篇日记。'}</p><div class="voice-wave" aria-hidden="true">${'<i></i>'.repeat(17)}</div><button class="record-button" data-action="mic" title="${s.recording?'结束录音':'开始录音'}" aria-label="${s.recording?'结束录音':'开始录音'}">${icons(s.recording?'pause':'mic')}</button><div class="timer">${s.recording?'00:'+String(s.seconds).padStart(2,'0'):'准备好了'}</div>${s.recording?'<p class="record-sub">录音中</p>':''}</section>`;
    return this.head('今天的日记') + `<main class="content record-content">${this.steps(0)}${modes}${inner}</main><footer class="action-bar">${s.mode==='text'?button('to-review','写好了，听一听','check','primary',!s.text.trim()):button('mic',s.recording?'说完了':'开始说','mic')}${button('save-draft','稍后再写','arrow-left','text-action')}</footer>`;
  }
  review() {
    const s = this.state;
    const text = s.editing ? `<label for="title-${this.root.id}">标题</label><input data-field="title" id="title-${this.root.id}" maxlength="40" value="${escapeText(s.title)}"><label for="review-${this.root.id}">日记正文</label><textarea data-field="text" id="review-${this.root.id}" maxlength="12000">${escapeText(s.text)}</textarea>` :
      `<h3 style="margin-bottom:16px">${escapeText(s.title)}</h3><p class="review-text">${escapeText(s.text).replace('大太阳','大<span class="highlight">太阳</span>')}</p>`;
    return this.head('确认这篇日记') + `<main class="content review-content">${this.steps(1)}<div class="review-title"><h2>${VARIANT==='book'?'听听这一页。':'听听，是这样吗？'}</h2><p>还是你的话，只整理了一点点。</p></div><div class="listen-row"><button class="listen" data-action="listen">${icons(s.speaking?'pause':'volume-2')}${s.speaking?'停止朗读':'听一遍'}</button>${tool('edit',s.editing?'check':'pencil',s.editing?'完成修改':'修改文字')}</div>${text}<button class="original-toggle" data-action="original" aria-expanded="${s.originalOpen}">${icons('book-open')}${s.originalOpen?'收起原来的话':'看看原来的话'}</button>${s.originalOpen?`<p class="original">${originalText}</p>${button('use-original','保留原来的话','rotate-ccw','text-action')}`:''}</main><footer class="action-bar">${button('save','就是这样，收起来','check','primary',!s.text.trim())}${button('record','我想再说一段','mic','text-action')}</footer>`;
  }
  read() {
    const s = this.state;
    const cover = s.coverBusy ? `<div class="cover-state"><div class="voice-wave" aria-hidden="true">${'<i></i>'.repeat(9)}</div><small>正在画这一天</small>${button('skip-cover','先读日记','book-open','text-action')}</div>` :
      s.skipCover ? `<div class="cover-state">${icons('book-open')}<small>这一页，先留给文字</small>${button('redo-cover','画张封面','pencil','text-action')}</div>` : photo(s.image, 'read-cover');
    return this.head('我的日记') + `<main class="content"><div class="read-layout"><div>${cover}<div class="read-tools"><p class="save-status">${icons('check')}日记已收好</p>${tool('redo-cover','rotate-ccw','重新画封面')}</div></div><article class="read-copy"><div class="eyebrow">9 月 7 日 · 星期一</div><h2>${escapeText(s.title)}</h2><p class="read-body">${escapeText(s.text)}</p>${VARIANT==='book'?'<div class="book-page-label"><span>小小日记</span><span>我的一天</span></div>':''}</article></div></main><footer class="action-bar">${button('listen',s.speaking?'停止朗读':'听我的故事',s.speaking?'pause':'volume-2')}${button('edit-read','改几个字','pencil','text-action')}</footer>`;
  }
  modal() {
    const s=this.state;
    if (!s.modal) return '';
    let heading, body;
    if (s.modal === 'calendar') {
      heading='翻到哪一月？';
      body=`<div class="month-strip"><button data-action="month-8" class="${s.month===8?'selected':''}">8 月</button><button data-action="month-9" class="${s.month===9?'selected':''}">9 月</button></div>`;
    } else if (s.modal === 'gate') {
      heading='请家长来帮忙'; body=`<label>16 + 7 = ?</label><input data-gate inputmode="numeric" aria-label="算式答案" autocomplete="off">${button('gate','进入家长空间','check')}`;
    } else {
      heading='家长空间'; body=`<label>文字接口</label><select aria-label="文字接口"><option>OpenAI 兼容</option><option>Anthropic 兼容</option></select><label>文字模型</label><input value="MiniMax-M2.5" aria-label="文字模型"><label>图片模型</label><input value="image-01" aria-label="图片模型"><div class="toggle-line"><span>确认时显示原文</span><input type="checkbox" aria-label="确认时显示原文" checked></div>${button('save-settings','保存','check')}`;
    }
    return `<div class="modal-shade"><section class="modal" role="dialog" aria-modal="true" aria-label="${heading}"><div class="modal-head"><h2>${heading}</h2>${tool('close-modal','x','关闭')}</div>${body}</section></div>`;
  }
  render() {
    this.root.innerHTML = this[this.state.screen]() + this.modal();
    if (this.state.modal) this.root.querySelector('.modal input,.modal button')?.focus({preventScroll:true});
  }
}

const params = new URLSearchParams(location.search);
const solo = params.get('mode') === 'app';
if (solo) document.body.classList.add('solo');
const apps = Array.from(document.querySelectorAll('.app')).map((root, index) => new DiaryPrototype(root,
  solo && index === 0 ? (['home','record','review','read'].includes(params.get('screen'))?params.get('screen'):'home') : root.dataset.initial));
window.addEventListener('pagehide', () => apps.forEach(app=>{clearInterval(app.timer);app.stopAudio();}));
document.addEventListener('keydown', event => { if(event.key==='Escape') apps.forEach(app=>{if(app.state.modal){app.state.modal='';app.render();}}); });
window.__ready = true;
