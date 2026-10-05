const { chromium } = require('C:/Users/DungLT/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
const fs = require('fs');
(async () => {
 const browser=await chromium.launch({channel:'msedge',headless:true});
 const page=await browser.newPage();
 const errors=[];page.on('pageerror',e=>errors.push(e.message));
 const results=[];
 for(const width of [375,768,1440]) {
  await page.setViewportSize({width,height:1000});
  await page.goto('file:///'+process.cwd().replace(/\\/g,'/')+'/docs/registration/rendered-register.html',{waitUntil:'networkidle',timeout:30000});
  await page.screenshot({path:`docs/registration/register-${width}.png`,fullPage:true});
  results.push(await page.evaluate(width=>({width,bodyWidth:document.documentElement.scrollWidth,overflow:document.documentElement.scrollWidth>width,
   heading:document.querySelector('h1').innerText,inputs:[...document.querySelectorAll('#main-content input')].map(e=>({name:e.name,width:e.getBoundingClientRect().width})),
   buttonColor:getComputedStyle(document.querySelector('#main-content button[type=submit]')).backgroundColor}),width));
 }
 fs.writeFileSync('docs/registration/browser-results.json',JSON.stringify({results,errors},null,2));
 await browser.close();
 console.log(JSON.stringify({results,errors}));
})().catch(e=>{console.error(e.message);process.exit(1)});
