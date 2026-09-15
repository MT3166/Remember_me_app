const fs = require('node:fs');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const { chromium } = require('C:/Users/honor/AppData/Local/npm-cache/_npx/e41f203b7505f1fb/node_modules/playwright');
const assert = require('node:assert/strict');

(async()=>{
  const browser=await chromium.launch({headless:true,args:['--disable-gpu'],executablePath:'C:/Users/honor/AppData/Local/ms-playwright/chromium-1234/chrome-win64/chrome.exe'});
  const report=[];
  fs.mkdirSync(path.join(__dirname,'screenshots'),{recursive:true});
  try{
    for(const variant of (process.argv[2] ? [process.argv[2]] : ['gallery','studio','book'])){
      const errors=[];
      const page=await browser.newPage({viewport:{width:1760,height:1100},deviceScaleFactor:1});
      page.on('pageerror',error=>errors.push(error.message));
      page.on('console',message=>{if(message.type()==='error')errors.push(message.text());});
      const url=pathToFileURL(path.join(__dirname,variant+'.html')).href;
      await page.goto(url);await page.evaluate(()=>document.fonts.ready);
      assert.equal(await page.locator('.app').count(),4);
      assert.equal(await page.locator('img').evaluateAll(images=>images.filter(i=>!i.complete||i.naturalWidth===0).length),0);
      await page.screenshot({path:path.join(__dirname,'screenshots',variant+'-overview.png'),fullPage:true});
      const app=page.locator('#home');
      await app.locator('.entry[data-action="entry-0"]').click();
      await app.locator('.read-copy').waitFor();
      await app.locator('[data-action="home"]').click();
      await app.locator('[data-action="record"]').first().click();
      await app.locator('.record-button').click();
      await app.locator('.is-recording').waitFor();
      await app.locator('.record-button').click();
      await app.locator('.review-text').waitFor();
      await app.locator('[data-action="original"]').click();
      assert.ok(await app.locator('.original').isVisible());
      await app.locator('[data-action="edit"]').click();
      await app.locator('[data-field="text"]').fill('');
      assert.ok(await app.locator('[data-action="save"]').isDisabled());
      await app.locator('[data-field="text"]').fill('这是改过的演示日记。');
      await app.locator('[data-action="save"]').click();
      assert.ok(await app.locator('.cover-state').isVisible());
      await app.locator('.read-cover').waitFor();
      assert.equal(await app.locator('.read-body').innerText(),'这是改过的演示日记。');
      await app.locator('[data-action="settings"]').click();
      await app.locator('[data-gate]').fill('23');
      await app.locator('[data-action="gate"]').click();
      await app.locator('select').selectOption({label:'Anthropic 兼容'});
      await app.locator('[data-action="save-settings"]').click();
      assert.equal(await app.locator('[role="dialog"]').count(),0);
      const layouts=[];
      for(const [name,width,height] of [['portrait',800,1280],['landscape',1280,800],['mobile',390,844]]){
        await page.setViewportSize({width,height});
        for(const screen of ['home','record','review','read']){
          await page.goto(`${url}?mode=app&screen=${screen}`);await page.evaluate(()=>document.fonts.ready);
          const dimensions=await page.evaluate(()=>({vw:innerWidth,scroll:document.documentElement.scrollWidth,
            vh:innerHeight,height:document.documentElement.scrollHeight}));
          assert.ok(dimensions.scroll<=width+1,`${variant} ${name} ${screen}: horizontal overflow`);
          const buttons=await page.locator('#home .action-bar>button').evaluateAll(buttons=>buttons.map(b=>{
            const r=b.getBoundingClientRect();return{bottom:r.bottom,top:r.top,height:r.height};}));
          assert.ok(buttons.every(b=>b.bottom<=height+1&&b.top>=0),`${variant} ${name} ${screen}: action outside screen`);
          assert.equal(await page.locator('#home img').evaluateAll(images=>images.filter(i=>!i.complete||i.naturalWidth===0).length),0);
          if(screen==='home'||screen==='review') await page.screenshot({path:path.join(__dirname,'screenshots',`${variant}-${name}-${screen}.png`)});
        }
        layouts.push(name);
      }
      assert.deepEqual(errors,[],`${variant}: browser errors`);
      report.push({variant,checks:['four independent screens','detail navigation','record start/stop','original toggle','editing and empty validation','save/cover transition','parent settings','no image failures','no page/console errors'],layouts});
      await page.close();
      console.log(`${variant}: PASS`);
    }
    fs.writeFileSync(path.join(__dirname,process.argv[2] ? `verification-${process.argv[2]}.json` : 'verification.json'),JSON.stringify(report,null,2));
  }finally{await browser.close();}
})().catch(error=>{console.error(error);process.exitCode=1});
