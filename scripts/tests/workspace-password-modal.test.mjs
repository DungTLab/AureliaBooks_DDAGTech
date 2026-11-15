import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import vm from 'node:vm';

export async function runTests() {
    const source = await readFile(
        new URL('../../src/main/resources/static/js/workspace.js', import.meta.url),
        'utf8'
    );
    const results = [];

    function createClassList(initial = []) {
        const classes = new Set(initial);
        return {
            add: (...names) => names.forEach((n) => classes.add(n)),
            remove: (...names) => names.forEach((n) => classes.delete(n)),
            contains: (name) => classes.has(name),
        };
    }

    function createElement(initial = {}) {
        const listeners = {};
        const attributes = {};
        return {
            listeners,
            attributes,
            dataset: {},
            disabled: false,
            textContent: '',
            classList: createClassList(),
            addEventListener(type, fn) {
                (listeners[type] ||= []).push(fn);
            },
            dispatchEvent(event) {
                (listeners[event.type] || []).forEach((fn) => fn(event));
            },
            setAttribute(name, value) {
                attributes[name] = String(value);
            },
            removeAttribute(name) {
                delete attributes[name];
            },
            querySelector: () => null,
            querySelectorAll: () => [],
            closest: () => null,
            ...initial,
        };
    }

    function runWorkspace({ssrForm = null, modal = null, fetchImpl}) {
        const fetchCalls = [];
        let reloadCalls = 0;
        const docListeners = {};
        const documentStub = {
            addEventListener(type, fn) {
                (docListeners[type] ||= []).push(fn);
            },
            querySelector(selector) {
                if (selector === 'form[data-password-change]') {
                    return ssrForm;
                }
                return null;
            },
            querySelectorAll: () => [],
            getElementById(id) {
                if (id === 'internal-password-modal') {
                    return modal;
                }
                return null;
            },
        };

        vm.runInNewContext(source, {
            document: documentStub,
            window: {
                location: {
                    reload() {
                        reloadCalls += 1;
                    },
                },
            },
            matchMedia: () => ({matches: true, addEventListener() {}}),
            CustomEvent: class {
                constructor(type) {
                    this.type = type;
                }
            },
            Event: class {
                constructor(type) {
                    this.type = type;
                }
            },
            FormData: class {
                constructor(form) {
                    this.form = form;
                }
            },
            fetch: async (...args) => {
                fetchCalls.push(args);
                return fetchImpl(...args);
            },
        });

        return {fetchCalls, getReloadCalls: () => reloadCalls};
    }

    function createModalFixture(submitLabel) {
        const submitBtn = createElement({textContent: submitLabel});
        const closeBtn = createElement();
        const errBox = createElement({classList: createClassList(['hidden'])});
        const errText = createElement();
        const modalForm = createElement({
            action: '/internal/account/change-password',
            checkValidity: () => true,
            querySelector: (selector) => (selector === 'button[type="submit"]' ? submitBtn : null),
        });
        const modal = createElement({
            id: 'internal-password-modal',
            querySelector(selector) {
                if (selector === 'form[data-ajax-submit][data-password-change]') return modalForm;
                if (selector === '[data-modal-error]') return errBox;
                if (selector === '[data-modal-error-text]') return errText;
                return null;
            },
            querySelectorAll(selector) {
                return selector === '[data-close-modal]' ? [closeBtn] : [];
            },
        });
        return {submitBtn, closeBtn, errBox, errText, modalForm, modal};
    }

    async function submitForm(form) {
        const event = {
            defaultPrevented: false,
            preventDefault() {
                this.defaultPrevented = true;
            },
        };
        for (const handler of form.listeners.submit || []) {
            await handler(event);
        }
        return event;
    }

    async function test(name, run) {
        await run();
        results.push(name);
    }

    await test('ignores SSR recovery reset form when #internal-password-modal is absent', async () => {
        const submitBtn = createElement({textContent: 'Đặt mật khẩu mới'});
        const ssrForm = createElement({
            action: '/internal/forgot-password/reset',
            checkValidity: () => true,
            querySelector: (selector) => (selector === 'button[type="submit"]' ? submitBtn : null),
        });

        const {fetchCalls} = runWorkspace({
            ssrForm,
            modal: null,
            fetchImpl: async () => {
                throw new Error('fetch should not be called for SSR recovery reset');
            },
        });

        const event = await submitForm(ssrForm);

        assert.equal(event.defaultPrevented, false);
        assert.equal(fetchCalls.length, 0);
        assert.equal(submitBtn.textContent, 'Đặt mật khẩu mới');
        assert.equal(submitBtn.disabled, false);
    });

    await test('submits #internal-password-modal form via AJAX and reloads on success', async () => {
        const {modalForm, modal} = createModalFixture('Lưu thay đổi');
        const {fetchCalls, getReloadCalls} = runWorkspace({
            modal,
            fetchImpl: async () => ({
                ok: true,
                json: async () => ({success: true}),
            }),
        });

        const event = await submitForm(modalForm);

        assert.equal(event.defaultPrevented, true);
        assert.equal(fetchCalls.length, 1);
        assert.equal(fetchCalls[0][0], '/internal/account/change-password');
        assert.equal(fetchCalls[0][1].credentials, 'same-origin');
        assert.equal(modal.classList.contains('hidden'), true);
        assert.equal(modal.attributes['aria-hidden'], 'true');
        assert.equal(getReloadCalls(), 1);
    });

    await test('shows modal error message and preserves custom submit label on API failure', async () => {
        const {submitBtn, closeBtn, errBox, errText, modalForm, modal} = createModalFixture('Cập nhật mật khẩu');
        const {getReloadCalls} = runWorkspace({
            modal,
            fetchImpl: async () => ({
                ok: false,
                json: async () => ({success: false, message: 'Mật khẩu hiện tại không đúng.'}),
            }),
        });

        const event = await submitForm(modalForm);

        assert.equal(event.defaultPrevented, true);
        assert.equal(errBox.classList.contains('hidden'), false);
        assert.equal(errText.textContent, 'Mật khẩu hiện tại không đúng.');
        assert.equal(submitBtn.disabled, false);
        assert.equal(submitBtn.textContent, 'Cập nhật mật khẩu');
        assert.equal(closeBtn.disabled, false);
        assert.equal(modalForm.dataset.inFlight, undefined);
        assert.equal(getReloadCalls(), 0);
    });

    return {passed: results.length, tests: results};
}
