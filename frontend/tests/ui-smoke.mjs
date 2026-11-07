// Browser-only API fixtures. This file is never bundled into the application.
// Set PLAYWRIGHT_PACKAGE_JSON to an existing installation's package.json, or install Playwright locally.
import { createRequire } from 'node:module'
import { mkdir, writeFile } from 'node:fs/promises'
import assert from 'node:assert/strict'
const require = createRequire(process.env.PLAYWRIGHT_PACKAGE_JSON || new URL('../package.json', import.meta.url))
const { chromium } = require('playwright')
const base = process.env.UI_BASE_URL || 'http://localhost:3000'
const output = new URL('../test-results/ui/', import.meta.url)
await mkdir(output, { recursive: true })
const unitId='10000000-0000-0000-0000-000000000001', stationId='20000000-0000-0000-0000-000000000001', userId='30000000-0000-0000-0000-000000000001', docId='40000000-0000-0000-0000-000000000001', templateId='50000000-0000-0000-0000-000000000001'
const unit={id:unitId,code:'TEST-UNIT',name:'หน่วยงานทดสอบหน้าจอ',is_active:true,provisioning_status:'READY',version:1}
const actor={id:userId,email:'ui.fixture@mfu.ac.th',display_name:'ผู้ทดสอบหน้าจอ',is_active:true,identity_status:'GOOGLE_LINKED',system_roles:['ADMIN','COURIER'],memberships:[{unit_id:unitId,role:'UNIT_ADMIN',is_active:true}],station_ids:[stationId],capabilities:['COURIER'],version:1}
const station={id:stationId,unit_id:unitId,code:'TEST-STATION',name:'Station ทดสอบหน้าจอ'}
const template={id:templateId,origin_unit_id:unitId,name:'เส้นทางทดสอบหน้าจอ',station_ids:[stationId,stationId],version:1}
const doc={id:docId,title:'หนังสือทดสอบการแสดงผลและการจัดวางข้อความภาษาไทย',document_number:'UI-FIXTURE-001',origin_unit_id:unitId,origin_station_id:stationId,tracking_mode:'MODEL_B',policy_version:1,route_snapshot:[{step_id:'s1',station_id:stationId,sequence:0},{step_id:'s2',station_id:stationId,sequence:1}],business_state:'WAIT_ORIGIN_READY',version:0,current_route_step_id:'s1',process_sync_status:'PENDING'}
const failures=[];const verified=[];const browser=await chromium.launch({headless:true, ...(process.env.BROWSER_CHANNEL ? {channel:process.env.BROWSER_CHANNEL} : {})})
try {
  for(const viewport of [{name:'desktop',width:1440,height:1000},{name:'mobile',width:390,height:844}]) {
    // Anonymous login is served by the real application; only the expected absent-session response is intercepted
    // so this check is repeatable while the local backend is being configured independently.
    const context=await browser.newContext({viewport})
    const page=await context.newPage();page.setDefaultTimeout(15000);page.on('pageerror',e=>failures.push(`${viewport.name}: ${e.message}`))
    await page.goto(`${base}/login`);await page.getByRole('link',{name:'เข้าสู่ระบบด้วย Google'}).waitFor()
    assert.equal(await page.getByRole('link',{name:'เข้าสู่ระบบด้วย Google'}).getAttribute('href'),'/api/v1/auth/google/start')
    assert.equal(await page.locator('input[type=password]').count(),0)
    assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false)
    await page.screenshot({path:new URL(`login-${viewport.name}.png`,output).pathname.replace(/^\/([A-Za-z]:)/,'$1'),fullPage:true})
    verified.push(`Google-only login ${viewport.name}: no password, correct backend redirect, no overflow`)
    await context.close()

    const signed=await browser.newContext({viewport});const authenticated=await signed.newPage();authenticated.setDefaultTimeout(15000);const commands=[]
    authenticated.on('pageerror',e=>failures.push(`${viewport.name}: ${e.message}`))
    await authenticated.route('**/api/v1/**',async route=>{
      const request=route.request(), path=new URL(request.url()).pathname.replace('/api/v1','')
      let body
      if(request.method()!=='GET') {
        assert.equal(request.headers()['x-csrf-token'],'fixture-csrf')
        if(request.method()!=='PUT'||!path.endsWith('/content'))assert.ok(request.headers()['idempotency-key'])
        commands.push({path,method:request.method(),body:request.postDataJSON()})
      }
      if(path==='/auth/me')body=actor
      else if(path==='/auth/csrf')body={header_name:'X-CSRF-TOKEN',token:'fixture-csrf'}
      else if(path==='/units')body=request.method()==='POST'?{...unit,...request.postDataJSON()}:{items:[unit],next_cursor:null}
      else if(path==='/users')body={items:[actor],next_cursor:null}
      else if(path==='/stations')body={items:[station]}
      else if(path==='/route-templates')body=[template]
      else if(path==='/documents')body=request.method()==='POST'?{document_ids:[docId],operation_id:'op-ui',process_sync_status:'PENDING'}:{items:[doc],next_cursor:null}
      else if(path===`/documents/${docId}`)body=doc
      else if(path===`/documents/${docId}/timeline`)body={items:[{event_id:'event-ui',event_type:'DOCUMENT_REGISTERED',sequence:1,recorded_at:'2026-11-06T06:00:00Z',actor_id:userId,evidence_ids:[]}],next_cursor:null}
      else if(path==='/admin/login-notifications')body={items:[],next_cursor:null}
      else if(path===`/stations/${stationId}/worklist`||path===`/stations/${stationId}/unit-worklist`)body={items:[{...doc,action:null,action_route_step_id:'s1'}],next_cursor:null}
      else if(path==='/courier/reservations')body={items:[],next_cursor:null}
      else {failures.push(`Unexpected API ${request.method()} ${path}`);return route.fulfill({status:404,json:{code:'FIXTURE_ROUTE_MISSING'}})}
      await route.fulfill({status:200,json:body})
    })
    for(const screen of [{path:'/admin/units',heading:'จัดการหน่วยงาน',file:'units'},{path:'/documents',heading:'หนังสือของหน่วยงาน',file:'registry'},{path:`/documents/${docId}`,heading:doc.title,file:'detail'},{path:'/admin/users',heading:'จัดการผู้ใช้หน่วยงาน',file:'users'},{path:'/worklist',heading:'งานประจำ Station',file:'worklist'}]) {
      console.log(`Checking ${viewport.name} ${screen.path}`)
      await authenticated.goto(`${base}${screen.path}`)
      await authenticated.getByRole('heading',{name:screen.heading,exact:true}).waitFor()
      await authenticated.waitForTimeout(200)
      assert.equal(await authenticated.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false,`${screen.path} overflow ${viewport.name}`)
      await authenticated.screenshot({path:new URL(`${screen.file}-${viewport.name}.png`,output).pathname.replace(/^\/([A-Za-z]:)/,'$1'),fullPage:true})
      verified.push(`${screen.path} ${viewport.name}: rendered, no horizontal viewport overflow`)
    }
    // These fixtures exercise slow/failed synchronization without changing the real worker or engine.
    for (const sync of ['PENDING','FAILED_RETRYABLE']) {
      doc.process_sync_status=sync
      await authenticated.goto(`${base}/documents/${docId}`)
      const ready=authenticated.getByTestId('document-ready')
      await ready.waitFor()
      assert.equal(await ready.isDisabled(),true)
      await authenticated.goto(`${base}/worklist`)
      await authenticated.getByTestId('station-select').waitFor()
      await authenticated.getByText('รอ workflow ประสาน',{exact:true}).waitFor()
      assert.equal(await authenticated.getByTestId(`ready-${docId}`).count(),0)
      verified.push(`${viewport.name}: ${sync} disables origin READY and exposes no unit mutation`)
    }
    doc.process_sync_status='PENDING'
    await authenticated.goto(`${base}/admin/units`)
    await authenticated.getByLabel('รหัสหน่วยงาน',{exact:true}).fill('UI-NEW')
    await authenticated.getByLabel('ชื่อหน่วยงาน',{exact:true}).fill('หน่วยงานเพิ่มจากทดสอบหน้าจอ')
    await authenticated.getByRole('button',{name:'บันทึกหน่วยงาน',exact:true}).click()
    await authenticated.getByRole('status').filter({hasText:'บันทึกหน่วยงานแล้ว'}).waitFor()
    assert.ok(commands.some(c=>c.path==='/units'&&c.body.code==='UI-NEW'))
    await authenticated.goto(`${base}/documents/new`)
    await authenticated.getByLabel('เลขหนังสือ',{exact:true}).fill('UI-NUMBER-ONLY')
    await authenticated.getByLabel('เส้นทางและเวอร์ชัน',{exact:true}).selectOption(`${templateId}:1`)
    await authenticated.getByRole('button',{name:'ลงทะเบียนหนังสือ',exact:true}).click()
    await authenticated.waitForURL(`**/documents/${docId}`)
    const registration=commands.find(c=>c.path==='/documents')
    assert.equal(registration.body.title,null)
    assert.equal(registration.body.document_number,'UI-NUMBER-ONLY')
    verified.push(`${viewport.name}: Unit create and number-only registration submit CSRF + idempotency headers`)
    await signed.close()
  }
  const unitAdminContext=await browser.newContext({viewport:{width:390,height:844}})
  const unitAdminPage=await unitAdminContext.newPage()
  let scopedUsers=false
  await unitAdminPage.route('**/api/v1/**',async route=>{
    const url=new URL(route.request().url()),path=url.pathname.replace('/api/v1','')
    let body
    if(path==='/auth/me')body={...actor,system_roles:[],station_ids:[],capabilities:[]}
    else if(path==='/units')body={items:[unit],next_cursor:null}
    else if(path==='/route-templates')body=[template]
    else if(path==='/stations')body={items:[station]}
    else if(path==='/users'){
      assert.equal(url.searchParams.get('unit_id'),unitId)
      scopedUsers=true;body={items:[],next_cursor:null}
    }else return route.fulfill({status:404,json:{code:'FIXTURE_ROUTE_MISSING'}})
    await route.fulfill({status:200,json:body})
  })
  await unitAdminPage.goto(`${base}/admin/users`)
  await unitAdminPage.getByLabel('หน่วยงานที่ดูแล',{exact:true}).waitFor()
  await unitAdminPage.getByRole('option',{name:'STAFF · เจ้าหน้าที่',exact:true}).waitFor({state:'attached'})
  assert.equal(await unitAdminPage.getByRole('option',{name:'UNIT_ADMIN · ผู้ดูแลหน่วยงาน',exact:true}).count(),0)
  assert.ok(scopedUsers)
  verified.push('Unit Admin mobile: users query scoped to administered unit; no Unit Admin creation option')
  await unitAdminPage.goto(`${base}/documents/new`)
  await unitAdminPage.getByLabel('เส้นทางและเวอร์ชัน',{exact:true}).selectOption(`${templateId}:1`)
  assert.equal(await unitAdminPage.getByLabel('เส้นทางและเวอร์ชัน',{exact:true}).inputValue(),`${templateId}:1`)
  verified.push('Origin Unit Admin without courier station grants can select server-authorized registration templates')
  await unitAdminContext.close()
  assert.deepEqual(failures,[])
  await writeFile(new URL('report.json',output),JSON.stringify({base,fixtureScope:'Authenticated screens use isolated browser request fixtures; no production mock provider',verified,failures},null,2))
  console.log(JSON.stringify({verified,failures},null,2))
} finally {await browser.close()}
