// Opt-in browser test against actual Spring HTTP + PostgreSQL + Flowable. No route interception or API mocks.
// Launched by P3BrowserIntegrationTest after generating fixture users/templates and a signed test-only OIDC provider.
import { createRequire } from 'node:module'
import { readFile, writeFile, mkdir } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { createServer, request as proxyRequest } from 'node:http'
import { join, resolve, extname, dirname } from 'node:path'
import assert from 'node:assert/strict'
const require=createRequire(process.env.PLAYWRIGHT_PACKAGE_JSON||'D:/git/DOCTRACKING/package.json')
const { chromium }=require('playwright')
const fixture=JSON.parse(await readFile(process.argv[2],'utf8'))
const output=dirname(process.argv[2]), publicRoot=fileURLToPath(new URL('../.output/public/',import.meta.url))
const base='http://127.0.0.1:62916', checks=[], failures=[], contexts=[]
const mime={'.html':'text/html; charset=utf-8','.js':'text/javascript','.css':'text/css','.json':'application/json','.svg':'image/svg+xml','.woff2':'font/woff2','.png':'image/png','.ico':'image/x-icon'}
const gateway=createServer(async(req,res)=>{
  if(req.url.startsWith('/api/')){
    const upstream=proxyRequest({hostname:'127.0.0.1',port:fixture.backendPort,path:req.url,method:req.method,headers:req.headers},response=>{res.writeHead(response.statusCode,response.headers);response.pipe(res)})
    upstream.on('error',()=>{res.writeHead(502);res.end('Backend unavailable')});req.pipe(upstream);return
  }
  try{
    let path=resolve(publicRoot,'.'+decodeURIComponent(new URL(req.url,base).pathname))
    if(!path.startsWith(resolve(publicRoot))){res.writeHead(403);res.end();return}
    let data;try{data=await readFile(path)}catch{path=join(publicRoot,'index.html');data=await readFile(path)}
    res.writeHead(200,{'Content-Type':mime[extname(path)]||'application/octet-stream'});res.end(data)
  }catch{res.writeHead(500);res.end('Build frontend before running P3 browser tests')}
})
await new Promise((ok,bad)=>{gateway.once('error',bad);gateway.listen(62916,'127.0.0.1',ok)})
const browser=await chromium.launch({headless:true,channel:process.env.BROWSER_CHANNEL||'chrome'})
let documentId
async function get(context,path){const response=await context.request.get(base+'/api/v1'+path);assert.equal(response.status(),200,`${path}: ${await response.text()}`);return response.json()}
async function mutate(context,path,body,csrf=true){const headers={'Idempotency-Key':crypto.randomUUID()};if(csrf)headers['X-CSRF-TOKEN']=(await get(context,'/auth/csrf')).token;return context.request.post(base+'/api/v1'+path,{headers,data:body})}
async function login(role){
  const context=await browser.newContext({viewport:{width:1440,height:1000}});contexts.push(context)
  const page=await context.newPage();page.setDefaultTimeout(15000);page.on('pageerror',error=>failures.push(`${role}: ${error.message}`))
  await page.goto(base+'/login');assert.equal(await page.locator('input[type=password]').count(),0)
  await page.locator('a[href="/api/v1/auth/google/start"]').click()
  await page.getByRole('button',{name:fixture.emails[role],exact:true}).click()
  await page.waitForURL(base+'/**');const me=await get(context,'/auth/me');assert.equal(me.email,fixture.emails[role])
  checks.push(`${role}: signed OIDC authorization code / PKCE / real JDBC session`)
  return {context,page}
}
async function state(origin,expected){
  const end=Date.now()+20000
  do{const doc=await get(origin.context,`/documents/${documentId}`);if(doc.business_state===expected&&doc.process_sync_status==='APPLIED')return doc;await new Promise(ok=>setTimeout(ok,150))}while(Date.now()<end)
  throw Error(`Expected ${expected} + APPLIED: ${JSON.stringify(await get(origin.context,`/documents/${documentId}`))}`)
}
async function eligible(courier,station,expected){
  const queue=await get(courier.context,`/stations/${station}/worklist`)
  assert.equal(queue.items.some(doc=>doc.id===documentId),expected,'Courier eligibility must follow explicit unit readiness')
}
async function work(actor,station,courier=false){
  await actor.page.goto(base+'/worklist')
  if(courier){const tab=actor.page.getByTestId('station-courier-tab');if(await tab.count())await tab.click()}
  await actor.page.getByTestId('station-select').selectOption(station)
}
async function photo(page){
  const png=await page.evaluate(()=>{const canvas=document.createElement('canvas');canvas.width=2;canvas.height=2;canvas.getContext('2d').fillRect(0,0,2,2);return canvas.toDataURL('image/png').split(',')[1]})
  await page.getByTestId('photo-input').setInputFiles({name:'p3-evidence.png',mimeType:'image/png',buffer:Buffer.from(png,'base64')})
  await page.getByTestId('photo-upload').click();await page.getByTestId('photo-ready').waitFor()
}
async function screenshot(page,name){await page.evaluate(async()=>{await document.fonts.ready;await new Promise(ok=>requestAnimationFrame(()=>requestAnimationFrame(ok)))});await page.screenshot({path:join(output,name+'.png'),fullPage:true,animations:'disabled'});assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false,name+' horizontal overflow')}
try{
  await mkdir(output,{recursive:true})
  const origin=await login('origin'),staffB=await login('staff-b'),staffC=await login('staff-c'),courier1=await login('courier-1'),courier2=await login('courier-2'),courier3=await login('courier-3'),outsider=await login('outsider')
  const couriers=[courier1,courier2,courier3]
  await courier1.page.setViewportSize({width:390,height:844})
  await origin.page.goto(base+'/documents/new')
  await origin.page.getByTestId('document-title').fill('P3 browser A → B → C → A '+fixture.run)
  await origin.page.getByTestId('document-template').selectOption(fixture.template+':1')
  await origin.page.getByTestId('document-register').click();await origin.page.waitForURL(/\/documents\/[0-9a-f-]{36}$/)
  documentId=new URL(origin.page.url()).pathname.split('/').at(-1)
  let doc=await state(origin,'WAIT_ORIGIN_READY')
  await eligible(courier1,fixture.stations[0],false)
  checks.push('T01: newly registered document is absent from courier queue before origin READY')
  const noCsrf=await mutate(origin.context,`/documents/${documentId}/ready`,{route_step_id:doc.current_route_step_id,expected_version:doc.version},false)
  assert.equal(noCsrf.status(),403);assert.equal((await get(origin.context,`/documents/${documentId}`)).version,doc.version)
  checks.push('Missing CSRF rejected without a workflow mutation')
  const denied=await outsider.context.request.get(`${base}/api/v1/documents/${documentId}`);assert.equal(denied.status(),404)
  checks.push('Unassigned signed-in actor cannot read another unit document')
  await origin.page.reload();await origin.page.getByTestId('document-ready').click();await state(origin,'READY_FOR_PICKUP')
  await eligible(courier1,fixture.stations[0],true)
  for(let source=0;source<3;source++){
    const courier=couriers[source]
    await work(courier,fixture.stations[source],true)
    await courier.page.getByTestId('reserve-'+documentId).click();await state(origin,'PICKUP_RESERVED')
    await courier.page.goto(base+'/reservations');await courier.page.getByTestId('pickup-open-'+documentId).click()
    if(source===0){
      const before=await get(origin.context,`/documents/${documentId}`)
      const pickupRequests=[];const record=request=>{if(request.method()==='POST'&&request.url().endsWith('/courier/check-receive'))pickupRequests.push(request.url())}
      courier.page.on('request',record)
      await courier.page.getByTestId('photo-input').setInputFiles({name:'broken.png',mimeType:'image/png',buffer:Buffer.from('not an actual image')})
      await courier.page.getByTestId('photo-upload').click();await courier.page.getByRole('alert').waitFor()
      assert.equal(await courier.page.getByTestId('courier-pickup').isDisabled(),true)
      assert.equal((await state(origin,'PICKUP_RESERVED')).version,before.version)
      assert.equal(pickupRequests.length,0);courier.page.off('request',record)
      checks.push('Real server rejects corrupt image finalization; pickup remains disabled and no pickup command or workflow transition occurs')
    }
    await photo(courier.page);await courier.page.getByTestId('physical-presence').check()
    if(source===0){await screenshot(courier.page,'pickup-mobile');checks.push('Actual mobile 390 px corrupt upload, valid upload, photo preview and physical-presence confirmation')}
    await courier.page.getByTestId('courier-pickup').click()
    doc=await state(origin,'IN_TRANSIT');assert.equal(doc.current_route_step_id,doc.route_snapshot[source].step_id)
    const wrongCourier=couriers[(source+1)%3]
    const wrongDrop=await mutate(wrongCourier.context,'/courier/check-dropoff',{station_id:fixture.stations[(source+1)%3],items:[{document_id:documentId,route_step_id:doc.route_snapshot[source+1].step_id,expected_version:doc.version,leg_id:doc.active_leg_id}],evidence_upload_ids:[]})
    assert.equal(wrongDrop.status(),403);assert.equal((await state(origin,'IN_TRANSIT')).version,doc.version)
    await courier.page.goto(base+'/legs');await courier.page.getByTestId('dropoff-open-'+documentId).click();await photo(courier.page)
    await courier.page.getByTestId('courier-dropoff').click()
    doc=await state(origin,source===2?'PENDING_ORIGIN_RETURN_RECEIPT':'PENDING_UNIT_RECEIPT')
    assert.equal(doc.current_route_step_id,doc.route_snapshot[source].step_id,'Dropoff must not synthesize unit receipt')
    assert.equal(doc.pending_receipt_step_id,doc.route_snapshot[source+1].step_id)
    if(source<2){
      const staff=source===0?staffB:staffC
      await eligible(couriers[source+1],fixture.stations[source+1],false)
      await work(staff,fixture.stations[source+1]);await staff.page.getByTestId('receipt-'+documentId).waitFor()
      if(source===0){await staff.page.setViewportSize({width:390,height:844});await screenshot(staff.page,'unit-receipt-mobile')}
      await staff.page.getByTestId('receipt-'+documentId).click();await state(origin,'UNIT_PROCESSING')
      await eligible(couriers[source+1],fixture.stations[source+1],false)
      await work(staff,fixture.stations[source+1]);await staff.page.getByTestId('ready-'+documentId).click();await state(origin,'READY_FOR_PICKUP')
      await eligible(couriers[source+1],fixture.stations[source+1],true)
      checks.push(`T03/T04 station ${source+1}: queue excludes dropoff-only and receipt-only documents; includes only after explicit READY`)
    }
    checks.push(`Leg ${source+1}: UI reservation → private photo → pickup → private photo → dropoff; destination receipt is explicit`)
  }
  assert.notEqual(doc.route_snapshot[0].step_id,doc.route_snapshot[3].step_id,'Repeat origin station retains unique route occurrence')
  const forbidden=await mutate(staffB.context,`/documents/${documentId}/confirm-return`,{route_step_id:doc.route_snapshot[3].step_id,expected_version:doc.version})
  assert.equal(forbidden.status(),403)
  const wrongOccurrence=await mutate(origin.context,`/documents/${documentId}/confirm-return`,{route_step_id:doc.route_snapshot[0].step_id,expected_version:doc.version})
  assert.ok(wrongOccurrence.status()>=400&&wrongOccurrence.status()<500)
  await state(origin,'PENDING_ORIGIN_RETURN_RECEIPT')
  await origin.page.goto(`${base}/documents/${documentId}`);await origin.page.getByTestId('document-return').click();doc=await state(origin,'COMPLETED')
  checks.push('Only origin membership with exact final route_step_id completes Model B; intermediate staff and initial occurrence rejected')
  await origin.page.reload();await origin.page.getByRole('heading',{name:'P3 browser A → B → C → A '+fixture.run,exact:true}).waitFor();await origin.page.locator('.timeline li').filter({hasText:'ต้นทางยืนยันรับคืน'}).waitFor();await screenshot(origin.page,'completed-desktop')
  await origin.page.setViewportSize({width:390,height:844});await screenshot(origin.page,'completed-mobile')
  await staffB.page.setViewportSize({width:390,height:844});await work(staffB,fixture.stations[1]);await screenshot(staffB.page,'station-mobile')
  const timeline=await get(origin.context,`/documents/${documentId}/timeline`)
  assert.equal(timeline.items.filter(e=>e.event_type==='ORIGIN_RETURN_CONFIRMED').length,1)
  assert.equal(timeline.items.filter(e=>e.event_type==='COURIER_PICKED_UP').length,3)
  assert.equal(timeline.items.filter(e=>e.event_type==='COURIER_DROPPED_OFF').length,3)
  const evidenceIds=timeline.items.flatMap(e=>e.evidence_ids)
  assert.equal(evidenceIds.length,6)
  for(const id of evidenceIds){const image=await origin.context.request.get(`${base}/api/v1/evidence/${id}/content`);assert.equal(image.status(),200);assert.match(image.headers()['content-type'],/^image\/png/)}
  assert.equal((await outsider.context.request.get(`${base}/api/v1/evidence/${evidenceIds[0]}/content`)).status(),404)
  checks.push('Origin downloads all six event-linked private PNGs; unrelated signed-in user receives 404')
  const csrf=(await get(outsider.context,'/auth/csrf')).token
  const logout=await outsider.context.request.post(base+'/api/v1/auth/logout',{headers:{'X-CSRF-TOKEN':csrf}})
  assert.ok(logout.ok());assert.equal((await outsider.context.request.get(base+'/api/v1/auth/me')).status(),401)
  checks.push('Logout revokes the real JDBC session; desktop and 390 px mobile have no horizontal overflow')
  assert.deepEqual(failures,[])
  await writeFile(join(output,'report.json'),JSON.stringify({document_id:documentId,scope:'Real browser + native HTTP gateway + Spring HTTP + PostgreSQL + Flowable; only external OIDC provider is test-local',checks,failures},null,2))
  console.log(JSON.stringify({document_id:documentId,checks,failures},null,2))
}catch(error){
  for(let i=0;i<contexts.length;i++){const page=contexts[i].pages()[0];if(page)await page.screenshot({path:join(output,`failure-${i}.png`),fullPage:true}).catch(()=>{})}
  console.error(error);process.exitCode=1
}finally{
  await Promise.all(contexts.map(c=>c.close()));await browser.close();gateway.closeAllConnections();await new Promise(ok=>gateway.close(ok))
}
