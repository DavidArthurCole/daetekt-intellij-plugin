const DEFAULT_STATE = {
    q: '',
    status: 'all',
    origin: 'all',
    flags: [],
    group: 'set',
    sort: 'name',
    open: [],
    collapsed: [],
};

const state = { ...DEFAULT_STATE, ...INITIAL?.state };
const openRules = new Set(state.open);
const collapsedGroups = new Set(state.collapsed);
const app = document.getElementById('app');

const FLAGS = [
    { key: 'autoCorrect', label: 'Auto-correct', test: r => r.autoCorrect },
    { key: 'typeRes', label: 'Type resolution', test: r => r.typeRes },
    { key: 'examples', label: 'Has examples', test: r => r.examples.length > 0 },
    { key: 'configured', label: 'Configured', test: r => r.options.some(o => o.value != null) },
];

const GROUPS = {
    set: { label: 'Rule set', key: r => r.set },
    origin: { label: 'Origin', key: r => r.custom ? 'Custom' : 'Built-in' },
    status: { label: 'Status', key: r => r.active ? 'Active' : 'Inactive' },
    jar: { label: 'Jar', key: r => r.jar || 'Unknown' },
    none: { label: 'None', key: () => '' },
};

const SORTS = {
    name: { label: 'Name', cmp: (a, b) => a.id.localeCompare(b.id, undefined, { sensitivity: 'base' }) },
    status: { label: 'Active first', cmp: (a, b) => (b.active - a.active) || SORTS.name.cmp(a, b) },
    custom: { label: 'Custom first', cmp: (a, b) => (b.custom - a.custom) || SORTS.name.cmp(a, b) },
    options: { label: 'Most options', cmp: (a, b) => (b.options.length - a.options.length) || SORTS.name.cmp(a, b) },
};

function send(type, value) {
    try {
        bridge(type + '\n' + (typeof value === 'string' ? value : JSON.stringify(value)));
    } catch (e) {
    }
}

let saveTimer = 0;

function save() {
    clearTimeout(saveTimer);
    saveTimer = setTimeout(() => {
        state.open = Array.from(openRules);
        state.collapsed = Array.from(collapsedGroups);
        send('state', { state: state, scroll: window.scrollY });
    }, 250);
}

function h(tag, attrs, ...children) {
    const el = document.createElement(tag);
    if (attrs) {
        for (const [key, value] of Object.entries(attrs)) {
            if (value == null || value === false) continue;
            if (key === 'class') el.className = value;
            else if (key === 'html') el.innerHTML = value;
            else if (key.startsWith('on')) el.addEventListener(key.slice(2), value);
            else el.setAttribute(key, value === true ? '' : value);
        }
    }
    for (const child of children.flat()) {
        if (child == null || child === false) continue;
        el.appendChild(typeof child === 'string' ? document.createTextNode(child) : child);
    }
    return el;
}

function escapeHtml(text) {
    return String(text).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
}

function escapeRegex(text) {
    return text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

function highlightText(text, term) {
    const safe = escapeHtml(text);
    if (!term) return safe;
    const words = term.split(/\s+/).filter(Boolean).map(w => escapeRegex(escapeHtml(w)));
    if (!words.length) return safe;
    return safe.replace(new RegExp(words.join('|'), 'gi'), m => '<mark>' + m + '</mark>');
}

function inlineMarkup(text, term) {
    const parts = String(text).split(/(`[^`]+`)/g);
    return parts.map(part => part.length > 2 && part.startsWith('`') && part.endsWith('`')
        ? '<code>' + escapeHtml(part.slice(1, -1)) + '</code>'
        : highlightText(part, term)).join('');
}

function paragraphs(text, term) {
    return String(text).split(/\n\s*\n/).map(block => {
        const p = document.createElement('p');
        p.innerHTML = inlineMarkup(block.replace(/\s*\n\s*/g, ' ').trim(), term);
        return p;
    });
}

const KEYWORDS = new Set(('as break class continue do else false for fun if in interface is null object package return super this throw ' +
    'true try typealias val var when while by catch constructor finally get import init set where abstract annotation companion ' +
    'const data enum external final inline inner internal lateinit open operator out override private protected public ' +
    'sealed suspend tailrec vararg value').split(' '));

const TOKEN = /(\/\/[^\n]*|\/\*[\s\S]*?\*\/)|("""[\s\S]*?"""|"(?:\\.|[^"\\\n])*"|'(?:\\.|[^'\\\n])*')|(@[A-Za-z_][\w.]*)|(\b\d[\d_]*(?:\.\d+)?[fFLuU]?\b|\b0x[0-9a-fA-F_]+\b)|([A-Za-z_]\w*)/g;

function highlightKotlin(code) {
    let out = '';
    let last = 0;
    code.replace(TOKEN, (match, comment, string, annotation, number, word, offset) => {
        out += escapeHtml(code.slice(last, offset));
        last = offset + match.length;
        let cls = null;
        if (comment) cls = 'tk-cmt';
        else if (string) cls = 'tk-str';
        else if (annotation) cls = 'tk-ann';
        else if (number) cls = 'tk-num';
        else if (word && KEYWORDS.has(word)) cls = 'tk-kw';
        out += cls ? '<span class="' + cls + '">' + escapeHtml(match) + '</span>' : escapeHtml(match);
        return match;
    });
    return out + escapeHtml(code.slice(last));
}

function codeBlock(kind, label, code) {
    return h('div', { class: 'code ' + kind },
        h('div', { class: 'label' }, label),
        h('pre', { html: highlightKotlin(code) }));
}

function matches(rule, term) {
    if (!term) return true;
    const haystack = [rule.id, rule.set, rule.desc, rule.jar, rule.cls, rule.rationale]
        .concat(rule.options.map(o => o.name))
        .filter(Boolean).join(' ').toLowerCase();
    return term.toLowerCase().split(/\s+/).filter(Boolean).every(word => haystack.includes(word));
}

function visibleRules() {
    const term = state.q.trim();
    return RULES.filter(rule =>
        (state.status === 'all' || (state.status === 'active') === rule.active) &&
        (state.origin === 'all' || (state.origin === 'custom') === rule.custom) &&
        FLAGS.every(flag => !state.flags.includes(flag.key) || flag.test(rule)) &&
        matches(rule, term));
}

function segmented(options, current, onPick) {
    return h('div', { class: 'segmented' }, options.map(([value, label]) =>
        h('button', { class: value === current ? 'on' : null, onclick: () => onPick(value) }, label)));
}

function select(label, options, current, onPick) {
    return h('label', { class: 'select' }, label,
        h('select', { onchange: e => onPick(e.target.value) },
            Object.entries(options).map(([value, option]) =>
                h('option', { value: value, selected: value === current }, option.label))));
}

function counts(list) {
    return {
        total: list.length,
        active: list.filter(r => r.active).length,
        custom: list.filter(r => r.custom).length,
    };
}

function toolbar(shown) {
    const all = counts(RULES);
    const input = h('input', {
        id: 'q',
        type: 'search',
        placeholder: 'Search ' + all.total + ' rules, options, classes',
        value: state.q,
        spellcheck: 'false',
        oninput: e => {
            state.q = e.target.value;
            renderResults();
            save();
        },
        onkeydown: e => {
            if (e.key === 'Escape' && state.q) {
                e.preventDefault();
                e.stopPropagation();
                state.q = '';
                render();
                save();
            }
        },
    });
    const filtersActive = state.q || state.status !== 'all' || state.origin !== 'all' || state.flags.length > 0;
    return h('header', { class: 'toolbar' },
        h('div', { class: 'search' }, input,
            state.q ? h('button', { class: 'clear', title: 'Clear', onclick: () => {
                state.q = '';
                render();
                save();
            } }, 'x') : null),
        h('div', { class: 'row' },
            segmented([['all', 'All'], ['active', 'Active ' + all.active], ['inactive', 'Inactive ' + (all.total - all.active)]],
                state.status, v => update({ status: v })),
            segmented([['all', 'Any origin'], ['builtin', 'Built-in'], ['custom', 'Custom ' + all.custom]],
                state.origin, v => update({ origin: v }))),
        h('div', { class: 'row' }, FLAGS.map(flag =>
            h('button', {
                class: 'chip' + (state.flags.includes(flag.key) ? ' on' : ''),
                onclick: () => update({
                    flags: state.flags.includes(flag.key) ? state.flags.filter(f => f !== flag.key) : state.flags.concat(flag.key),
                }),
            }, flag.label))),
        h('div', { class: 'row spread' },
            h('div', { class: 'row' },
                select('Group', GROUPS, state.group, v => update({ group: v })),
                select('Sort', SORTS, state.sort, v => update({ sort: v }))),
            h('div', { class: 'row' },
                h('span', { class: 'summary', id: 'summary' }, summaryText(shown)),
                filtersActive ? h('button', { class: 'ghost', onclick: () => update({ q: '', status: 'all', origin: 'all', flags: [] }) }, 'Reset') : null,
                h('button', { class: 'ghost', onclick: () => setAllOpen(true) }, 'Expand'),
                h('button', { class: 'ghost', onclick: () => setAllOpen(false) }, 'Collapse'),
                h('button', { class: 'ghost', title: 'Reload rules from the current settings', onclick: () => send('reload', '') }, 'Reload'))));
}

function summaryText(shown) {
    const c = counts(shown);
    return c.total + ' shown, ' + c.active + ' active' + (c.custom ? ', ' + c.custom + ' custom' : '');
}

function update(patch) {
    Object.assign(state, patch);
    render();
    save();
}

function setAllOpen(open) {
    if (open) {
        visibleRules().forEach(r => openRules.add(key(r)));
        collapsedGroups.clear();
    } else {
        openRules.clear();
    }
    renderResults();
    save();
}

function key(rule) {
    return rule.set + '/' + rule.id;
}

function plural(count, word) {
    return count + ' ' + word + (count === 1 ? '' : 's');
}

function badges(rule) {
    return h('span', { class: 'badges' },
        rule.custom ? h('span', { class: 'badge custom', title: rule.jar || '' }, 'Custom') : null,
        rule.autoCorrect ? h('span', { class: 'badge fix' }, 'Auto-correct') : null,
        rule.typeRes ? h('span', { class: 'badge type', title: 'Skipped during on-the-fly analysis' }, 'Type resolution') : null,
        rule.examples.length ? h('span', { class: 'badge' }, plural(rule.examples.length, 'example')) : null,
        rule.options.length ? h('span', { class: 'badge' }, plural(rule.options.length, 'option')) : null);
}

function examplesSection(rule) {
    if (!rule.examples.length) return null;
    return h('section', null,
        h('h4', null, rule.examples.length === 1 ? 'Example' : 'Examples'),
        rule.examples.map(example => h('div', { class: 'example' },
            example.title ? h('div', { class: 'example-title' }, example.title) : null,
            h('div', { class: 'pair' },
                example.before != null ? codeBlock('bad', 'Noncompliant', example.before) : null,
                example.after != null ? codeBlock('good', 'Compliant', example.after) : null),
            example.note ? h('div', { class: 'note', html: inlineMarkup(example.note, '') }) : null)));
}

function optionsSection(rule, term) {
    if (!rule.options.length) return null;
    return h('section', null,
        h('h4', null, 'Configuration'),
        h('table', { class: 'options' },
            h('thead', null, h('tr', null, h('th', null, 'Option'), h('th', null, 'Value'), h('th', null, 'Description'))),
            h('tbody', null, rule.options.map(option => {
                const overridden = option.value != null && option.value !== option.def;
                const value = option.value != null ? option.value : option.def;
                return h('tr', null,
                    h('td', null, h('code', { html: highlightText(option.name, term) })),
                    h('td', { class: 'value' },
                        h('span', { class: overridden ? 'overridden' : null, title: overridden ? 'Set in your configuration' : null }, value != null ? value : '-'),
                        overridden && option.def != null ? h('div', { class: 'hint' }, 'default ' + option.def) : null),
                    h('td', { html: option.desc ? inlineMarkup(option.desc, term) : '<span class="hint">-</span>' }));
            }))));
}

function configSnippet(rule) {
    return rule.set + ':\n  ' + rule.id + ':\n    active: ' + (!rule.active) + '\n';
}

function actions(rule) {
    return h('div', { class: 'actions' },
        rule.src ? h('a', { title: rule.cls, onclick: () => send('source', rule.cls) }, 'Open ' + rule.src) : null,
        rule.url ? h('a', { title: rule.url, onclick: () => send('open', rule.url) }, 'Documentation') : null,
        h('a', { title: configSnippet(rule), onclick: () => {
            send('copy', configSnippet(rule));
            toast('Copied config to ' + (rule.active ? 'disable' : 'enable') + ' ' + rule.id);
        } }, rule.active ? 'Copy disable config' : 'Copy enable config'),
        h('a', { onclick: () => {
            send('copy', rule.id);
            toast('Copied ' + rule.id);
        } }, 'Copy ID'),
        rule.cls ? h('span', { class: 'cls' }, rule.cls) : null);
}

function ruleView(rule, term, showSet) {
    const id = key(rule);
    const open = openRules.has(id);
    const article = h('article', { class: 'rule' + (open ? ' open' : '') + (rule.active ? '' : ' inactive') });
    const head = h('div', {
        class: 'rule-head',
        onclick: e => {
            if (e.target.closest('a')) return;
            if (openRules.has(id)) openRules.delete(id); else openRules.add(id);
            article.replaceWith(ruleView(rule, state.q.trim(), showSet));
            save();
        },
    },
        h('span', { class: 'chev' }),
        h('span', { class: 'dot' + (rule.active ? '' : ' off'), title: rule.active ? 'Active' : 'Inactive' }),
        h('h3', { html: highlightText(rule.id, term) }),
        showSet ? h('span', { class: 'set' }, rule.set) : null,
        badges(rule));
    article.appendChild(head);
    article.appendChild(h('div', { class: 'desc', html: rule.desc ? inlineMarkup(rule.desc, term) : '<span class="hint">No description.</span>' }));
    if (open) {
        article.appendChild(h('div', { class: 'details' },
            rule.rationale ? h('section', null, h('h4', null, 'Details'), paragraphs(rule.rationale, term)) : null,
            examplesSection(rule),
            optionsSection(rule, term),
            actions(rule)));
    }
    return article;
}

function groupView(name, rules, term) {
    const id = state.group + ':' + name;
    const c = counts(rules);
    const jar = state.group === 'set' ? rules.map(r => r.jar).find(Boolean) : null;
    const section = h('section', { class: 'group' + (collapsedGroups.has(id) && !term ? ' collapsed' : '') },
        h('div', {
            class: 'group-head',
            onclick: () => {
                if (collapsedGroups.has(id)) collapsedGroups.delete(id); else collapsedGroups.add(id);
                section.classList.toggle('collapsed');
                save();
            },
        },
            h('h2', null, name),
            h('span', { class: 'meta' }, c.active + ' of ' + c.total + ' active'),
            jar ? h('span', { class: 'meta' }, jar) : null,
            h('span', { class: 'chev' })),
        h('div', { class: 'rules' }, rules.map(rule => ruleView(rule, term, state.group !== 'set'))));
    return section;
}

function resultsView() {
    const shown = visibleRules().slice().sort(SORTS[state.sort] ? SORTS[state.sort].cmp : SORTS.name.cmp);
    const term = state.q.trim();
    const main = h('main', { id: 'results' });
    if (!RULES.length) {
        main.appendChild(h('p', { class: 'empty' }, MESSAGE || 'No detekt rules loaded.'));
        return { main, shown };
    }
    if (!shown.length) {
        main.appendChild(h('p', { class: 'empty' }, 'No matching rules.'));
        return { main, shown };
    }
    if (state.group === 'none' || !GROUPS[state.group]) {
        main.appendChild(h('div', { class: 'rules' }, shown.map(rule => ruleView(rule, term, true))));
        return { main, shown };
    }
    const group = GROUPS[state.group];
    const groups = new Map();
    for (const rule of shown) {
        const name = group.key(rule);
        if (!groups.has(name)) groups.set(name, []);
        groups.get(name).push(rule);
    }
    Array.from(groups.keys())
        .sort((a, b) => a.localeCompare(b, undefined, { sensitivity: 'base' }))
        .forEach(name => main.appendChild(groupView(name, groups.get(name), term)));
    return { main, shown };
}

function renderResults() {
    const { main, shown } = resultsView();
    const current = document.getElementById('results');
    if (current) current.replaceWith(main); else app.appendChild(main);
    const summary = document.getElementById('summary');
    if (summary) summary.textContent = summaryText(shown);
}

function render() {
    const scroll = window.scrollY;
    const focused = document.activeElement && document.activeElement.id === 'q';
    const caret = focused ? document.activeElement.selectionStart : null;
    app.textContent = '';
    const { main, shown } = resultsView();
    if (RULES.length) app.appendChild(toolbar(shown));
    app.appendChild(main);
    if (focused) {
        const input = document.getElementById('q');
        input.focus();
        input.setSelectionRange(caret, caret);
    }
    window.scrollTo(0, scroll);
}

let toastTimer = 0;

function toast(text) {
    let el = document.querySelector('.toast');
    if (!el) {
        el = h('div', { class: 'toast' });
        document.body.appendChild(el);
    }
    el.textContent = text;
    el.classList.add('show');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => el.classList.remove('show'), 1600);
}

document.addEventListener('keydown', e => {
    const input = document.getElementById('q');
    if (!input || e.target === input) return;
    if ((e.key === 'f' && (e.ctrlKey || e.metaKey)) || e.key === '/') {
        e.preventDefault();
        input.focus();
        input.select();
    }
});

window.addEventListener('scroll', () => save(), { passive: true });

render();
if (typeof INITIAL?.scroll === 'number') window.scrollTo(0, INITIAL.scroll);
