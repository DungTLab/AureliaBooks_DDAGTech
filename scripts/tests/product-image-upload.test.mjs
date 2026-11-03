import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import vm from 'node:vm';

export async function runTests() {
    const source = await readFile(new URL('../../src/main/resources/static/js/product-image-upload.js', import.meta.url), 'utf8');
    const imageUrl = '/products/images/550e8400-e29b-41d4-a716-446655440000';
    const old = 'https://example.com/old.png';
    const results = [];
    function fixture(fetchImpl) {
        const element = (values = {}) => ({listeners: {}, dataset: {}, ...values,
            addEventListener(name, callback) { this.listeners[name] = callback; },
            removeAttribute(name) { delete this[name]; }});
        const url = element({value: old});
        const file = element({files: [{size: 128, type: 'image/png'}]});
        const button = element();
        const status = element();
        const preview = element({hidden: true});
        const csrf = element({value: 'test-token', dataset: {header: 'X-CSRF-TOKEN'}});
        const form = element({querySelector: () => url, dispatchEvent: () => {}});
        const elements = {'[data-image-file]': file, '[data-image-upload]': button,
            '[data-image-status]': status, '[data-image-preview]': preview, '[data-image-csrf]': csrf};
        const panel = {dataset: {uploadUrl: '/manager/products/images'}, closest: () => form,
            querySelector: name => elements[name]};
        const calls = [];
        const revoked = [];
        vm.runInNewContext(source, {
            document: {querySelectorAll: () => [panel]},
            window: {addEventListener() {}}, TypeError,
            Event: class {constructor(type) {this.type = type;}},
            FormData: class {append(name, value) {this[name] = value;}},
            URL: {createObjectURL: () => 'blob:preview', revokeObjectURL: x => revoked.push(x)},
            fetch: async (...args) => {calls.push(args); return fetchImpl(...args);}
        });
        return {url, file, button, status, preview, csrf, form, calls, revoked,
            click: () => button.listeners.click()};
    }
    async function test(name, run) {await run(); results.push(name);}
    await test('success sets canonical URL and sends multipart CSRF headers', async () => {
        const f = fixture(async () => ({ok: true, json: async () => ({imageUrl})}));
        f.file.listeners.change();
        assert.equal(f.preview.hidden, false);
        await f.click();
        assert.equal(f.url.value, imageUrl);
        assert.equal(f.calls[0][1].headers['X-CSRF-TOKEN'], 'test-token');
        assert.equal(f.calls[0][1].credentials, 'same-origin');
        assert.equal(f.calls[0][1].headers['Content-Type'], undefined);
        assert.equal(f.calls[0][1].body.file, f.file.files[0]);
        assert.equal(f.preview.hidden, false); // Preview stays local until DB commit.
    });
    await test('invalid file never sends request or changes URL', async () => {
        const f = fixture(() => {throw new Error('Unexpected request');});
        f.file.files = [{size: 6 * 1024 * 1024, type: 'image/png'}];
        await f.click();
        assert.equal(f.calls.length, 0);
        assert.equal(f.url.value, old);
    });
    await test('storage failure preserves old URL and restores controls', async () => {
        const f = fixture(async () => ({ok: false, status: 503}));
        await f.click();
        assert.equal(f.url.value, old);
        assert.match(f.status.textContent, /gián đoạn/);
        assert.equal(f.button.disabled, false);
        assert.equal(f.url.readOnly, false);
        assert.equal(f.form.dataset.serverBlocked, undefined);
    });
    await test('403 gives actionable message without altering previous URL', async () => {
        const f = fixture(async () => ({ok: false, status: 403}));
        await f.click();
        assert.equal(f.url.value, old);
        assert.match(f.status.textContent, /tải lại trang/);
    });
    await test('429 keeps URL and asks the user to wait', async () => {
        const f = fixture(async () => ({ok: false, status: 429}));
        await f.click();
        assert.equal(f.url.value, old);
        assert.match(f.status.textContent, /đợi/);
    });
    await test('invalid response cannot replace old URL', async () => {
        const f = fixture(async () => ({ok: true, json: async () => ({imageUrl: 'javascript:alert(1)'})}));
        await f.click();
        assert.equal(f.url.value, old);
        assert.match(f.status.textContent, /không hợp lệ/);
    });
    await test('missing CSRF never uploads', async () => {
        const f = fixture(() => {throw new Error('Unexpected request');});
        f.csrf.value = '';
        await f.click();
        assert.equal(f.calls.length, 0);
        assert.equal(f.url.value, old);
    });
    await test('in-flight upload blocks submit and duplicates then restores prior block state', async () => {
        let resolve;
        const f = fixture(() => new Promise(done => {resolve = done;}));
        f.form.dataset.serverBlocked = 'false';
        const pending = f.click();
        let prevented = false;
        f.form.listeners.submit({preventDefault() {prevented = true;}, stopImmediatePropagation() {}});
        await f.click();
        assert.equal(prevented, true);
        assert.equal(f.calls.length, 1);
        resolve({ok: true, json: async () => ({imageUrl})});
        await pending;
        assert.equal(f.form.dataset.serverBlocked, 'false');
        assert.equal(f.file.disabled, false);
    });
    await test('manual URL change releases local object URL', async () => {
        const f = fixture(async () => ({ok: true}));
        f.file.listeners.change();
        f.url.listeners.input();
        assert.deepEqual(f.revoked, ['blob:preview']);
        assert.equal(f.preview.hidden, true);
    });
    return {passed: results.length, tests: results};
}
