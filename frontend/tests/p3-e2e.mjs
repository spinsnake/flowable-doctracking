// Opt-in browser test against actual Spring HTTP + PostgreSQL + Flowable. No route interception or API mocks.
// Launched by P3BrowserIntegrationTest after generating fixture users/templates and a signed test-only OIDC provider.
import { createRequire } from 'node:module'
import { execFile } from 'node:child_process'
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
  if(req.url.startsWith('/api/')||req.url==='/healthz'){
    const upstream=proxyRequest({hostname:'127.0.0.1',port:fixture.backendPort,path:req.url==='/healthz'?'/actuator/health/readiness':req.url,method:req.method,headers:req.headers},response=>{res.writeHead(response.statusCode,response.headers);response.pipe(res)})
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
let documentId,modelAId,observationId,newBId;let bulkDocumentIds=[]
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
async function register(origin,title,expected){
  await origin.page.goto(base+'/documents/new');await origin.page.getByTestId('document-title').fill(title+' '+fixture.run)
  await origin.page.getByTestId('document-template').selectOption(fixture.template+':1');await origin.page.getByTestId('document-register').click()
  await origin.page.waitForURL(/\/documents\/[0-9a-f-]{36}$/);documentId=new URL(origin.page.url()).pathname.split('/').at(-1)
  return state(origin,expected)
}
async function policy(admin,mode,reason){
  await admin.page.goto(base+'/admin/workflow-policy');await admin.page.getByTestId('policy-mode').selectOption(mode);await admin.page.getByTestId('policy-reason').fill(reason)
  const response=admin.page.waitForResponse(r=>r.request().method()==='PUT'&&new URL(r.url()).pathname==='/api/v1/admin/workflow-policy')
  await admin.page.getByTestId('policy-save').click();assert.equal((await response).status(),200)
  assert.equal((await get(admin.context,'/admin/workflow-policy')).default_tracking_mode,mode)
}
async function transport(origin,courier,source,expected){
  await work(courier,fixture.stations[source],true);await courier.page.getByTestId('reserve-'+documentId).click();await state(origin,'PICKUP_RESERVED')
  await courier.page.goto(base+'/reservations');await courier.page.getByTestId('pickup-open-'+documentId).click();await photo(courier.page)
  await courier.page.getByTestId('physical-presence').check();await courier.page.getByTestId('courier-pickup').click();await state(origin,'IN_TRANSIT')
  await courier.page.goto(base+'/legs');await courier.page.getByTestId('dropoff-open-'+documentId).click();await photo(courier.page);await courier.page.getByTestId('courier-dropoff').click()
  return state(origin,expected)
}
async function observe(courier,station,exactStep,reason){
  await courier.page.goto(base+'/observations');await courier.page.getByTestId('observation-document').fill(documentId)
  await courier.page.getByTestId('observation-station').selectOption(station);await courier.page.getByTestId('observation-load').click()
  await courier.page.getByTestId('observation-step').selectOption(exactStep);await photo(courier.page)
  await courier.page.getByTestId('observation-reason').fill(reason);await courier.page.getByTestId('observation-physical').check()
  const response=courier.page.waitForResponse(r=>r.request().method()==='POST'&&r.url().endsWith('/observations'))
  await courier.page.getByTestId('observation-submit').click();const result=await response;assert.equal(result.status(),200);return result.json()
}
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
  if(fixture.p4){
    const admin=await login('admin'),originalB=documentId,bSnapshot=JSON.stringify([doc.tracking_mode,doc.policy_version,doc.route_snapshot,doc.current_route_step_id,doc.version])
    assert.equal((await origin.context.request.get(base+'/api/v1/admin/workflow-policy')).status(),403)
    await policy(admin,'MODEL_A','P4 B to A while original B remains active')
    const a=await register(origin,'P4 Model A delivery','AWAIT_PHYSICAL_PICKUP');modelAId=documentId
    await origin.page.getByRole('heading',{name:'P4 Model A delivery '+fixture.run,exact:true}).waitFor()
    assert.equal(await origin.page.getByTestId('document-ready').count(),0)
    assert.equal(await origin.page.getByTestId('document-return').count(),0)
    await eligible(courier1,fixture.stations[0],true)
    const observation=await register(origin,'P4 physical finding','AWAIT_PHYSICAL_PICKUP');observationId=documentId
    await policy(admin,'MODEL_B','P4 A to B while Model A instances remain active')
    const aAfter=await get(origin.context,`/documents/${modelAId}`)
    assert.deepEqual([aAfter.tracking_mode,aAfter.policy_version,aAfter.route_snapshot,aAfter.version],[a.tracking_mode,a.policy_version,a.route_snapshot,a.version])
    const newB=await register(origin,'P4 new B after switch','WAIT_ORIGIN_READY');newBId=documentId
    assert.equal(newB.tracking_mode,'MODEL_B');assert.ok(newB.policy_version>a.policy_version)
    const oldB=await get(origin.context,`/documents/${originalB}`)
    assert.equal(JSON.stringify([oldB.tracking_mode,oldB.policy_version,oldB.route_snapshot,oldB.current_route_step_id,oldB.version]),bSnapshot)
    checks.push('T17/T18/T19: Admin UI B→A→B affects new registrations; existing active A/B mode, policy, route and version unchanged')
    documentId=modelAId
    await work(courier1,fixture.stations[0],true);await courier1.page.getByTestId('reserve-'+documentId).waitFor()
    await courier1.page.getByTestId('filter-mode').selectOption('MODEL_B');await courier1.page.getByTestId('reserve-'+documentId).waitFor({state:'detached'})
    await courier1.page.getByTestId('filter-mode').selectOption('MODEL_A');await courier1.page.getByTestId('filter-state').selectOption('AWAIT_PHYSICAL_PICKUP')
    await courier1.page.getByTestId('reserve-'+documentId).waitFor();await courier1.page.getByTestId('filter-mode').selectOption('');await courier1.page.getByTestId('filter-state').selectOption('')
    checks.push('Model/business-state filters operate on loaded courier worklist rows; Model B filter hides Model A without changing eligibility')
    for(let source=0;source<3;source++){
      doc=await transport(origin,couriers[source],source,source===2?'COURIER_TRACKING_COMPLETE':'DROPPED_OFF_UNCONFIRMED')
      assert.equal(doc.pending_receipt_step_id,null)
      if(source<2)await eligible(couriers[source+1],fixture.stations[source+1],true)
    }
    const aTimeline=await get(origin.context,`/documents/${modelAId}/timeline`)
    assert.equal(aTimeline.items.filter(e=>['UNIT_READY','UNIT_RECEIPT_CONFIRMED','ORIGIN_RETURN_CONFIRMED'].includes(e.event_type)).length,0)
    assert.equal(aTimeline.items.filter(e=>e.event_type==='COURIER_PICKED_UP').length,3)
    await origin.page.goto(`${base}/documents/${modelAId}`);await origin.page.locator('.timeline li').last().waitFor()
    await origin.page.getByText('ติดตามการนำส่งครบแล้ว',{exact:true}).waitFor();await screenshot(origin.page,'model-a-desktop')
    await origin.page.setViewportSize({width:390,height:844});await screenshot(origin.page,'model-a-mobile');await origin.page.setViewportSize({width:1440,height:1000})
    checks.push('T07/T08/T09: Model A traverses A→B→C→A with three couriers and six photos, no READY/unit receipt, ends tracking complete')
    documentId=observationId
    const current=await observe(courier1,fixture.stations[0],observation.route_snapshot[0].step_id,'Physically found at the exact initial occurrence')
    assert.equal(current.reconciliation_status,'NOT_REQUIRED');await state(origin,'AWAIT_PHYSICAL_PICKUP')
    doc=await transport(origin,courier1,0,'DROPPED_OFF_UNCONFIRMED')
    const before=doc.current_route_step_id
    await courier2.page.setViewportSize({width:390,height:844})
    const gap=await observe(courier2,fixture.stations[2],doc.route_snapshot[2].step_id,'Physically found at C; B to C carrier and time are unknown')
    assert.equal(gap.reconciliation_status,'REQUIRES_REVIEW');doc=await state(origin,'EXCEPTION')
    assert.equal(doc.current_route_step_id,before);assert.equal(doc.reported_location_step_id,before)
    await courier2.page.getByRole('status').filter({hasText:'บันทึกตำแหน่งและช่องว่างการนำส่งแล้ว'}).waitFor();await screenshot(courier2.page,'observation-mobile')
    const gapTimeline=await get(origin.context,`/documents/${observationId}/timeline`)
    const missing=gapTimeline.items.find(e=>e.event_type==='UNOBSERVED_TRANSFER')
    assert.equal(missing.actor_id,null);assert.equal(missing.observation.transfer_started_at,null);assert.equal(missing.observation.transfer_arrived_at,null)
    await origin.page.goto(`${base}/documents/${observationId}`);await origin.page.locator('.timeline li').filter({hasText:'พบช่องว่างการนำส่งที่ไม่มีหลักฐาน'}).waitFor();await screenshot(origin.page,'gap-timeline-desktop')
    await admin.page.goto(base+'/admin/workflow-policy');await admin.page.getByTestId('policy-version').waitFor();await screenshot(admin.page,'policy-desktop')
    await admin.page.setViewportSize({width:390,height:844});await screenshot(admin.page,'policy-mobile')
    checks.push('T10 conservative baseline: same-step observation records evidence; B→C gap has unknown carrier/time and opens exception without advancing current/reported/task')
    documentId=originalB;doc=await state(origin,'WAIT_ORIGIN_READY');await origin.page.goto(`${base}/documents/${originalB}`)
  }
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
  if(fixture.p5){
    const mainDocumentId=documentId,bulkDocs=[]
    for(let i=0;i<10;i++){
      const created=await mutate(origin.context,'/documents',{title:`P5 bulk ${i+1} ${fixture.run}`,origin_station_id:fixture.stations[0],template_id:fixture.template,template_version:1})
      assert.equal(created.status(),202);documentId=(await created.json()).document_ids[0]
      let row=await state(origin,'WAIT_ORIGIN_READY')
      assert.equal((await mutate(origin.context,`/documents/${documentId}/ready`,{route_step_id:row.current_route_step_id,expected_version:row.version})).status(),202)
      row=await state(origin,'READY_FOR_PICKUP');bulkDocs.push(row)
    }
    await courier1.page.setViewportSize({width:1440,height:1000});await work(courier1,fixture.stations[0],true)
    for(const row of bulkDocs)await courier1.page.getByRole('checkbox',{name:`เลือกหนังสือ ${row.id}`,exact:true}).check()
    // A second real courier reserves two after the first screen loaded: no API interception.
    const contested=await mutate(courier2.context,'/courier/reservations',{station_id:fixture.stations[0],items:bulkDocs.slice(8).map(row=>({document_id:row.id,route_step_id:row.current_route_step_id,expected_version:row.version}))})
    assert.equal(contested.status(),201)
    const reservedResponse=courier1.page.waitForResponse(r=>r.request().method()==='POST'&&new URL(r.url()).pathname==='/api/v1/courier/reservations')
    await courier1.page.getByTestId('bulk-submit').click();const reservedHttp=await reservedResponse;assert.equal(reservedHttp.status(),201)
    const reserved=await reservedHttp.json();assert.equal(reserved.accepted.length,8);assert.equal(reserved.rejected.length,2);assert.ok(reserved.rejected.every(r=>r.code==='RESERVATION_CONFLICT'))
    bulkDocumentIds=reserved.accepted
    await courier1.page.getByTestId('batch-result').getByRole('heading',{name:'สำเร็จ 8 เรื่อง · ไม่สำเร็จ 2 เรื่อง',exact:true}).waitFor()
    assert.equal(await courier1.page.getByTestId('batch-result').getByText(/มีผู้จองไปแล้ว/).count(),2)
    await screenshot(courier1.page,'bulk-conflict-desktop');await courier1.page.setViewportSize({width:390,height:844});await screenshot(courier1.page,'bulk-conflict-mobile')
    checks.push('P5 D08: real stale selection of10 commits8 immediately, reports2 already reserved, no hidden retry')
    await courier1.page.goto(base+'/reservations')
    for(const id of bulkDocumentIds)await courier1.page.getByRole('checkbox',{name:`เลือกรับหนังสือ ${id}`,exact:true}).check()
    await courier1.page.getByTestId('bulk-pickup-open').click();await photo(courier1.page);await courier1.page.getByTestId('physical-presence').check()
    await courier1.page.getByRole('button',{name:'แนบภาพเพิ่ม',exact:true}).click();assert.equal(await courier1.page.getByTestId('courier-pickup').isDisabled(),true)
    await courier1.page.getByRole('button',{name:'นำช่องภาพสุดท้ายออก',exact:true}).click();await courier1.page.getByTestId('courier-pickup').click()
    for(const id of bulkDocumentIds){documentId=id;await state(origin,'IN_TRANSIT')}
    await courier1.page.goto(base+'/legs')
    for(const id of bulkDocumentIds)await courier1.page.getByRole('checkbox',{name:`เลือกส่งหนังสือ ${id}`,exact:true}).check()
    await courier1.page.getByTestId('bulk-dropoff-open').click();await photo(courier1.page);await courier1.page.getByTestId('courier-dropoff').click()
    for(const id of bulkDocumentIds){documentId=id;await state(origin,'PENDING_UNIT_RECEIPT')}
    await staffB.page.setViewportSize({width:1440,height:1000});await work(staffB,fixture.stations[1])
    for(const id of bulkDocumentIds)await staffB.page.getByRole('checkbox',{name:`เลือกหนังสือ ${id}`,exact:true}).check()
    await staffB.page.getByTestId('bulk-submit').click()
    for(const id of bulkDocumentIds){documentId=id;await state(origin,'UNIT_PROCESSING')}
    await staffB.page.getByTestId('batch-result').getByRole('heading',{name:'สำเร็จ 8 เรื่อง · ไม่สำเร็จ 0 เรื่อง',exact:true}).waitFor()
    await screenshot(staffB.page,'bulk-receipt-desktop');await staffB.page.setViewportSize({width:390,height:844});await screenshot(staffB.page,'bulk-receipt-mobile')
    checks.push('P5:8 actual pickups with one shared photo,8 same-destination dropoffs,8 explicit unit receipts; all real Flowable ACKs')
    checks.push('P5: additional unfinished photo blocks command until explicitly removed; batch review exposes all selected document IDs/count')
    documentId=mainDocumentId
  }
  if(fixture.p6){
    await new Promise((ok,bad)=>execFile(process.env.PYTHON_BIN||'python',['scripts/operations.py','smoke','--origin',base,'--allow-http-localhost'],{timeout:60000},(error,stdout,stderr)=>error?bad(new Error(stderr||error.message)):ok(stdout)))
    checks.push('P6: anonymous loopback smoke validates real readiness, CSRF and absent session without triggering login/email')
    const admin=await login('admin'),mainDocument=documentId
    assert.equal((await outsider.context.request.get(base+'/api/v1/admin/operations/summary')).status(),403)
    assert.equal((await admin.context.request.get(base+`/api/v1/documents/${mainDocument}`)).status(),404)
    await admin.page.goto(base+'/admin/operations');await admin.page.getByRole('heading',{name:'ติดตามและกู้คืนระบบ',exact:true}).waitFor()
    await admin.page.getByTestId('ops-retry-'+fixture.failed_delivery).click();await admin.page.getByTestId('ops-retry-reason').fill('P6 actual browser retry original canonical delivery')
    const retried=admin.page.waitForResponse(r=>r.request().method()==='POST'&&r.url().includes('/retry'))
    await admin.page.getByTestId('ops-retry-submit').click();assert.equal((await retried).status(),202)
    documentId=fixture.failed_document;await state(origin,'WAIT_ORIGIN_READY')
    await admin.page.getByTestId('ops-scan-open').click();await admin.page.locator('#ops-scan-document').fill(fixture.mismatched_document);await admin.page.getByTestId('ops-scan-reason').fill('P6 browser detect exact task-local mismatch without automatic correction')
    const scanned=admin.page.waitForResponse(r=>r.request().method()==='POST'&&r.url().endsWith('/reconciliation/scan'))
    await admin.page.getByTestId('ops-scan-submit').click();const scanResponse=await scanned;assert.equal(scanResponse.status(),200);assert.equal((await scanResponse.json()).mismatches,1)
    await admin.page.getByTestId('ops-tab-reconciliation').click();await admin.page.getByText(/TASK_BINDING_MISMATCH|TASK_STEP_MISMATCH|TASK_CORRELATION_MISMATCH/).first().waitFor()
    await admin.page.getByRole('button',{name:'ตรวจขั้นตอน',exact:true}).first().click();await admin.page.getByTestId('ops-engine').waitFor()
    await screenshot(admin.page,'operations-desktop');await admin.page.setViewportSize({width:390,height:844});await screenshot(admin.page,'operations-mobile')
    checks.push('P6: CentralAdmin UI retries original failed delivery with reason; canonical event opens one real process and audit; no business re-registration')
    checks.push('P6: read-only reconciliation detects exact task-local mismatch, preserves task/document version, displays scoped findings and technical bindings')
    checks.push('P6: admin technical access does not grant document detail; unrelated user operations denied; desktop/mobile no overflow')
    documentId=mainDocument
  }
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
  await writeFile(join(output,'report.json'),JSON.stringify({document_id:documentId,bulk_document_ids:bulkDocumentIds,model_a_id:modelAId,observation_id:observationId,new_b_id:newBId,scope:'Real browser + native HTTP gateway + Spring HTTP + PostgreSQL + Flowable; only external OIDC provider is test-local',checks,failures},null,2))
  console.log(JSON.stringify({document_id:documentId,checks,failures},null,2))
}catch(error){
  for(let i=0;i<contexts.length;i++){const page=contexts[i].pages()[0];if(page)await page.screenshot({path:join(output,`failure-${i}.png`),fullPage:true}).catch(()=>{})}
  console.error(error);process.exitCode=1
}finally{
  await Promise.all(contexts.map(c=>c.close()));await browser.close();gateway.closeAllConnections();await new Promise(ok=>gateway.close(ok))
}
