"""Actual HTTP -> local Nginx -> Tomcat -> real JPA/MySQL QA. No Cloudflare calls.
Run with a live persistence QA harness and scripts/r2-e2e/proxy.py start.
"""
import concurrent.futures, http.client, http.cookiejar, json, pathlib, re, struct, sys, time, unittest
import urllib.request, urllib.error, urllib.parse, uuid, zlib
ROOT=pathlib.Path(__file__).resolve().parents[2]
QA=json.loads((ROOT/'target/r2-persistence-qa.json').read_text())
PROXY=json.loads((ROOT/'target/r2-qa-proxy.json').read_text())
BASE=PROXY['url']; PAUSED=PROXY['pausedUrl']

def png():
    def chunk(kind,body): return struct.pack('!I',len(body))+kind+body+struct.pack('!I',zlib.crc32(kind+body)&0xffffffff)
    return b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('!2I5B',2,2,8,2,0,0,0))+chunk(b'IDAT',zlib.compress((b'\0'+b'\xc2\x3c\x1a'*2)*2))+chunk(b'IEND',b'')
PNG=png()
class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self,*args,**kwargs): return None
class Response:
    def __init__(self,status,headers,body): self.status,self.headers,self.body=status,headers,body
    def text(self): return self.body.decode('utf8',errors='replace')
    def json(self): return json.loads(self.body)
class Client:
    def __init__(self,base=BASE):
        self.base=base; self.jar=http.cookiejar.CookieJar(); self.token=''
        self.opener=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.jar),NoRedirect())
    def request(self,path,method='GET',body=None,headers=None):
        request=urllib.request.Request(self.base+path,data=body,method=method,headers=headers or {})
        try:
            with self.opener.open(request,timeout=25) as r: return Response(r.status,r.headers,r.read())
        except urllib.error.HTTPError as r: return Response(r.code,r.headers,r.read())
    def refresh(self):
        page=self.request('/products/qa-session')
        if page.status!=200: raise AssertionError('QA CSRF page unavailable: '+str(page.status))
        self.token=re.search(r'<meta name="_csrf" content="([^"]+)"',page.text()).group(1)
    def login(self,name):
        page=self.request('/internal/login')
        token=re.search(r'<meta name="_csrf" content="([^"]+)"',page.text()).group(1)
        response=self.form('/internal/login',{'username':f'qa-{name}@example.test','password':'QaOnly-NotReal-391','_csrf':token})
        if response.status!=302: raise AssertionError('Synthetic login failed: '+str(response.status))
        self.refresh(); return self
    def form(self,path,values):
        return self.request(path,'POST',urllib.parse.urlencode(values,doseq=True).encode(),{'Content-Type':'application/x-www-form-urlencoded'})
    def upload(self,data=PNG,token='current'):
        boundary='qa-'+uuid.uuid4().hex
        body=(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="cover.png"\r\nContent-Type: image/png\r\n\r\n').encode()+data+f'\r\n--{boundary}--\r\n'.encode()
        headers={'Content-Type':'multipart/form-data; boundary='+boundary,'Accept':'application/json','X-Requested-With':'XMLHttpRequest'}
        if token is not None: headers['X-CSRF-TOKEN']=self.token if token=='current' else token
        return self.request('/manager/products/images','POST',body,headers)
    def control(self,action,**values):
        response=self.form('/manager/qa-control',{'action':action,'_csrf':self.token,**values})
        if response.status!=200: raise AssertionError('QA control failed: '+str(response.status))
    def snapshot(self): return self.request('/manager/qa-state').json()

class Checks(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.writer=Client().login('writer'); cls.control=Client().login('manager')
        cls.expiry=Client().login('expiry'); cls.quota=Client().login('quota')
        cls.code='897'+str(uuid.uuid4().int%10**9).zfill(9)
        cls.isbn='978'+str(uuid.uuid4().int%10**10).zfill(10)
    def image(self,url,status):
        response=Client().request(url)
        self.assertEqual(response.status,status)
        self.assertEqual(response.headers.get('Cache-Control'),'no-store')
        self.assertEqual(response.headers.get('X-Content-Type-Options'),'nosniff')
        self.assertIsNone(response.headers.get('Location'))
        if status==200:
            self.assertEqual(response.headers.get_content_type(),'image/png')
            self.assertTrue(response.body.startswith(b'\x89PNG\r\n\x1a\n'))
    def book_form(self,url=None,**extra):
        values={'barcode':self.code,'title':'R2 SQL QA Book','categoryId':QA['categoryId'],'price':'100000','originalCost':'60000',
                'weightGrams':'200','isbn':self.isbn,'publisherId':QA['publisherId'],'authorIds':QA['authorId'],
                'mainImageUrl':url or self.book_url,'_csrf':self.writer.token}
        values.update(extra); return values
    def pen_form(self,url=None,**extra):
        values={'barcode':str(int(self.code)+1),'title':'R2 SQL QA Pen','categoryId':QA['categoryId'],'price':'20000','originalCost':'10000',
                'brandId':QA['brandId'],'mainImageUrl':url or self.pen_url,'_csrf':self.writer.token}
        values.update(extra); return values
    def row(self,barcode):
        matches=[r for r in self.control.snapshot()['products'] if r['barcode']==barcode]
        self.assertEqual(len(matches),1,'Expected one committed SQL row'); return matches[0]
    def test_01_database_guard(self): self.assertEqual(self.control.snapshot()['database'],'r2_image_qa')
    def test_02_anonymous_with_valid_csrf_is_401(self):
        client=Client(); client.refresh()
        self.assertEqual(client.upload().status,401)
    def test_03_wrong_roles_with_valid_csrf_are_403(self):
        before=len(self.control.snapshot()['objectIds'])
        for role in ['admin','sale','customer']:
            with self.subTest(role=role):
                response=Client().login(role).upload()
                self.assertEqual(response.status,403); self.assertIsNone(response.headers.get('Location'))
        self.assertEqual(len(self.control.snapshot()['objectIds']),before)
    def test_04_csrf_missing_or_invalid_is_403(self):
        for token in [None,'not-a-valid-token']:
            with self.subTest(token='missing' if token is None else 'invalid'):
                response=self.expiry.upload(token=token)
                self.assertEqual(response.status,403); self.assertIsNone(response.headers.get('Location'))
    def test_05_context_path_form_actions(self):
        for kind in ['books','stationeries']:
            html=self.writer.request('/manager/'+kind+'/new').text()
            self.assertTrue('action="/qa/manager/'+kind+'"' in html,'Product form action must retain /qa context path')
    def test_06_upload_returns_canonical_url(self):
        response=self.writer.upload(); self.assertEqual(response.status,201)
        type(self).book_url=response.json()['imageUrl']
        self.assertRegex(self.book_url,r'^/products/images/[0-9a-f-]{36}$')
    def test_07_orphan_before_save_404(self): self.image(self.book_url,404)
    def test_08_create_book_real_sql_commit(self):
        response=self.writer.form('/manager/books',self.book_form())
        self.assertEqual(response.status,302)
        type(self).book_id=self.row(self.code)['id']
        self.assertEqual(self.row(self.code)['main_image_url'],self.book_url)
        self.assertEqual(self.row(self.code)['stock_quantity'],0)
    def test_09_book_readback_and_anonymous_png(self):
        self.assertTrue('value="'+self.book_url+'"' in self.writer.request(f'/manager/books/{self.book_id}/edit').text())
        self.image(self.book_url,200)
    def test_10_stationery_real_sql_commit_and_png(self):
        response=self.writer.upload(); self.assertEqual(response.status,201)
        type(self).pen_url=response.json()['imageUrl']; self.image(self.pen_url,404)
        response=self.writer.form('/manager/stationeries',self.pen_form()); self.assertEqual(response.status,302)
        type(self).pen_id=self.row(str(int(self.code)+1))['id']
        self.assertEqual(self.row(str(int(self.code)+1))['main_image_url'],self.pen_url)
        self.image(self.pen_url,200)
    def test_11_commit_failure_rolls_back_and_preserves_form(self):
        response=self.writer.upload(); self.assertEqual(response.status,201)
        type(self).replacement=response.json()['imageUrl']
        self.control.control('commit-fail',enabled='true')
        response=self.writer.form(f'/manager/books/{self.book_id}/edit',self.book_form(self.replacement,id=self.book_id,title='Must roll back'))
        # Assert database rollback even if the error page itself is incorrect.
        self.assertEqual(self.row(self.code)['main_image_url'],self.book_url)
        self.assertEqual(self.row(self.code)['title'],'R2 SQL QA Book')
        self.image(self.replacement,404); self.image(self.book_url,200)
        self.assertEqual(response.status,503,'Commit failure must re-render form with HTTP 503, not redirect')
        self.assertTrue('value="'+self.replacement+'"' in response.text(),'Uploaded URL must survive save failure')
    def test_11b_stationery_commit_failure_preserves_form(self):
        self.control.control('commit-fail',enabled='true')
        response=self.writer.form(f'/manager/stationeries/{self.pen_id}/edit',self.pen_form(self.book_url,id=self.pen_id,title='Must roll back pen'))
        self.assertEqual(self.row(str(int(self.code)+1))['main_image_url'],self.pen_url)
        self.assertEqual(response.status,503)
        self.assertTrue('value="'+self.book_url+'"' in response.text(),'Stationery form lost image URL')
    def test_11c_create_book_failure_leaves_no_partial_row(self):
        before=len(self.control.snapshot()['products']); self.control.control('commit-fail',enabled='true')
        response=self.writer.form('/manager/books',self.book_form(self.replacement,barcode=str(int(self.code)+2),isbn='978'+str(uuid.uuid4().int%10**10).zfill(10)))
        self.assertEqual(len(self.control.snapshot()['products']),before)
        self.assertEqual(response.status,503)
        self.assertTrue('value="'+self.replacement+'"' in response.text(),'Failed create lost uploaded image URL')
        self.image(self.replacement,404)
    def test_11d_create_stationery_failure_leaves_no_partial_row(self):
        before=len(self.control.snapshot()['products']); self.control.control('commit-fail',enabled='true')
        response=self.writer.form('/manager/stationeries',self.pen_form(self.replacement,barcode=str(int(self.code)+3)))
        self.assertEqual(len(self.control.snapshot()['products']),before)
        self.assertEqual(response.status,503)
        self.assertTrue('value="'+self.replacement+'"' in response.text(),'Failed stationery create lost image URL')
    def test_12_retry_same_image_can_commit(self):
        response=self.writer.form(f'/manager/books/{self.book_id}/edit',self.book_form(self.replacement,id=self.book_id,title='R2 SQL QA Book retried'))
        self.assertEqual(response.status,302)
        self.assertEqual(self.row(self.code)['main_image_url'],self.replacement)
        self.image(self.replacement,200); self.image(self.book_url,404)
    def test_13_storage_failure_preserves_sql_and_objects(self):
        before=self.control.snapshot(); self.control.control('storage-fail',enabled='true')
        try: self.assertEqual(self.writer.upload().status,503)
        finally: self.control.control('storage-fail',enabled='false')
        after=self.control.snapshot()
        self.assertEqual(before['objectIds'],after['objectIds']); self.assertEqual(before['products'],after['products'])
    def test_14_shared_references_visibility(self):
        self.assertEqual(self.writer.form(f'/manager/stationeries/{self.pen_id}/edit',self.pen_form(self.replacement,id=self.pen_id)).status,302)
        self.control.control('active',productId=self.book_id,enabled='false'); self.image(self.replacement,200)
        self.control.control('active',productId=self.pen_id,enabled='false'); self.image(self.replacement,404)
        self.control.control('active',productId=self.book_id,enabled='true')
        self.control.control('active',productId=self.pen_id,enabled='true'); self.image(self.replacement,200)
        self.assertTrue(self.book_url.split('/')[-1] in self.control.snapshot()['objectIds'],'Old orphan must not be deleted')
    def test_15_list_images_retain_proxy_context(self):
        html=self.writer.request('/manager/products?type=BOOK').text()
        self.assertTrue('src="/qa'+self.replacement+'"' in html,'Rendered internal image src must include /qa')
    def test_16_missing_or_stale_session_rejected(self):
        self.expiry.control('expire'); time.sleep(1.3)
        response=self.expiry.upload()
        self.assertIn(response.status,[401,403]); self.assertIsNone(response.headers.get('Location'))
    def test_17_old_csrf_after_new_session_is_rejected(self):
        client=Client().login('expiry'); old=client.token
        client.form('/internal/logout',{'_csrf':client.token}); client.login('expiry')
        self.assertEqual(client.upload(token=old).status,403)
    def test_18_rate_limit_429_retry_after(self):
        for _ in range(QA['quota']): self.assertEqual(self.quota.upload().status,201)
        response=self.quota.upload(); self.assertEqual(response.status,429)
        self.assertEqual(response.headers.get('Retry-After'),'60'); self.assertIsNone(response.headers.get('Location'))
    def test_19_concurrent_uploads_are_bounded(self):
        first=Client().login('parallel'); second=Client().login('parallel'); third=Client().login('parallel')
        self.control.control('hold',enabled='true')
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as executor:
            a=executor.submit(first.upload); b=executor.submit(second.upload)
            try:
                deadline=time.monotonic()+5
                while self.control.snapshot()['inFlight']<2 and time.monotonic()<deadline: time.sleep(.05)
                self.assertEqual(self.control.snapshot()['inFlight'],2)
                response=third.upload(); self.assertEqual(response.status,429)
                self.assertEqual(response.headers.get('Retry-After'),'1')
            finally: self.control.control('hold',enabled='false')
            self.assertEqual(a.result(timeout=10).status,201); self.assertEqual(b.result(timeout=10).status,201)
        self.assertEqual(self.control.snapshot()['inFlight'],0)
    def oversized(self,base,chunked):
        client=Client(base).login('expiry')
        parsed=urllib.parse.urlparse(base); connection=http.client.HTTPConnection(parsed.hostname,parsed.port,timeout=15)
        req=urllib.request.Request(base+'/manager/products/images'); client.jar.add_cookie_header(req)
        headers={'Cookie':req.get_header('Cookie'),'X-CSRF-TOKEN':client.token,'Accept':'application/json','X-Requested-With':'XMLHttpRequest',
                 'Content-Type':'multipart/form-data; boundary=qa-size'}
        if not chunked: headers['Content-Length']=str(7*1024*1024)
        else: headers['Transfer-Encoding']='chunked'
        connection.putrequest('POST',parsed.path+'/manager/products/images')
        for key,value in headers.items(): connection.putheader(key,value)
        connection.endheaders()
        if chunked:
            parts=[b'--qa-size\r\nContent-Disposition: form-data; name="file"; filename="big.png"\r\nContent-Type: image/png\r\n\r\n']+[bytes(1024*1024)]*7+[b'\r\n--qa-size--\r\n']
            try:
                for data in parts: connection.send(f'{len(data):x}\r\n'.encode()+data+b'\r\n')
                connection.send(b'0\r\n\r\n')
            except (BrokenPipeError,ConnectionResetError): pass
        response=connection.getresponse(); status=response.status; headers=dict(response.getheaders()); response.read(); connection.close()
        self.assertEqual(status,413); self.assertNotIn('Location',headers)
        return headers
    def test_20_proxy_content_length_limit(self): self.oversized(BASE,False)
    def test_21_proxy_chunked_limit(self): self.oversized(BASE,True)
    def test_22_direct_servlet_chunked_limit(self):
        headers=self.oversized(QA['url'],True)
        self.assertTrue('application/json' in headers.get('Content-Type',''))
    def test_23_upload_pause_preserves_real_sql_get(self):
        paused=Client(PAUSED).login('manager')
        html=paused.request('/manager/books/new').text()
        self.assertTrue('data-image-uploader' not in html)
        self.assertEqual(paused.upload().status,503)
        response=Client(PAUSED).request(self.replacement)
        self.assertEqual(response.status,200); self.assertEqual(response.headers.get_content_type(),'image/png')
    def test_24_external_url_round_trip(self):
        external='https://example.com/legacy.png?x=1&y=2'
        response=self.writer.form(f'/manager/stationeries/{self.pen_id}/edit',self.pen_form(external,id=self.pen_id))
        self.assertEqual(response.status,302)
        self.assertEqual(self.row(str(int(self.code)+1))['main_image_url'],external)
        type(self).external=external
    def test_25_no_backend_ports_in_redirects(self):
        response=Client().request('/manager/books')
        self.assertEqual(response.status,302)
        location=response.headers.get('Location','')
        parsed=urllib.parse.urlparse(location)
        self.assertTrue(parsed.path.endswith('/qa/internal/login'))
        self.assertTrue(not parsed.netloc or parsed.netloc==urllib.parse.urlparse(BASE).netloc,'Proxy redirect leaked backend origin')

if __name__=='__main__':
    result=unittest.TextTestRunner(verbosity=2,stream=sys.stdout).run(unittest.defaultTestLoader.loadTestsFromTestCase(Checks))
    report={'testsRun':result.testsRun,'passed':result.testsRun-len(result.failures)-len(result.errors),'failures':len(result.failures)+len(result.errors),
            'failedTests':[str(test) for test,_ in result.failures+result.errors], 'database':'r2_image_qa','nginxImage':PROXY['image']}
    (ROOT/'target/r2-persistence-http-results.json').write_text(json.dumps(report,indent=2))
    sys.exit(0 if result.wasSuccessful() else 1)
