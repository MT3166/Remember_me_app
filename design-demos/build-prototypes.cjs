const fs = require('node:fs');
const path = require('node:path');
const dir = __dirname;
const variants = [
  { variant:'gallery', title:'A · 故事画廊', description:'用日期与封面组织回忆。记录入口始终清楚，界面安静，让孩子的故事成为主角。', home:'封面与日期形成清楚的日记墙；减少装饰，把“说说今天”留作唯一主要动作。' },
  { variant:'studio', title:'B · 声音画室', description:'把“说一说”放在中心。明亮的黄色、清楚的操作反馈，以及说、听、收三个轻松步骤。', home:'更适合还不熟悉文字的孩子：大录音入口、可以按下的触感、少量次要选择。' },
  { variant:'book', title:'C · 我的小书页', description:'像翻开一本属于自己的书。柔和的书页、清楚的页边层级，让亲子阅读成为记录的延续。', home:'从最新一页开始翻阅，旧日记排成书页目录；记录像给自己的小书添一页。' }
];
variants.push({variant:'studio',file:'studio-green',title:'B · 声音画室 · 绿色版',description:'已选方向：大录音入口与说、听、收流程，改用叶绿与深绿。竖屏优先，横屏并排阅读。',home:'绿色录音入口与最近的小故事；确认后再收好日记，让孩子一次只处理一步。'});
const assets = {};
for (const file of ['painting.jpg','leaves.jpg','bread.jpg','display-font.woff2','body-font-0.woff2','body-font-1.woff2','body-font-2.woff2']) {
  const mime = file.endsWith('.woff2') ? 'font/woff2' : 'image/jpeg';
  assets[file] = `data:${mime};base64,${fs.readFileSync(path.join(dir,'assets',file)).toString('base64')}`;
}
const icons = Object.fromEntries(fs.readdirSync(path.join(dir,'assets','icons')).filter(f=>f.endsWith('.svg'))
  .map(f=>[f.slice(0,-4),fs.readFileSync(path.join(dir,'assets','icons',f),'utf8')]));
const template=fs.readFileSync(path.join(dir,'prototype-template.html'),'utf8');
for(const variant of variants){
  if (process.argv[2] && (variant.file || variant.variant) !== process.argv[2]) continue;
  let css=fs.readFileSync(path.join(dir,'prototype.css'),'utf8')+'\n'+fs.readFileSync(path.join(dir,variant.variant+'.css'),'utf8');
  if (variant.file) css+='\n'+fs.readFileSync(path.join(dir,variant.file+'.css'),'utf8');
  css=css.replaceAll('@@asset:display-font.woff2@@',assets['display-font.woff2']);
  for (const [index,weight] of [400,600,700].entries()) css += `\n@font-face{font-family:DiarySans;src:url('${assets['body-font-'+index+'.woff2']}');font-weight:${weight};font-display:swap}`;
  css += '\nbody{font-family:DiarySans,"Segoe UI","Microsoft YaHei",sans-serif}button.primary .icon,.record-button .icon,.studio-start .icon{filter:none}.record-button{color:white!important}\n';
  const replacements={title:variant.title,description:variant.description,filename:(variant.file||variant.variant)+'.html',variant:variant.variant,
    'variant-json':JSON.stringify(variant.variant),'home-caption':variant.home,css,assets:JSON.stringify(assets),icons:JSON.stringify(icons),
    script:fs.readFileSync(path.join(dir,'prototype.js'),'utf8')};
  const html=template.replace(/@@([a-z-]+)@@/g,(_,key)=>replacements[key]);
  fs.writeFileSync(path.join(dir,(variant.file||variant.variant)+'.html'),html);
  console.log(`Built ${variant.file||variant.variant}.html`);
}
