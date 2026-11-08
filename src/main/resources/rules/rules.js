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
const ui = { segments: {}, chips: {}, selects: {} };

const ICONS = {
    search: '<circle cx="7" cy="7" r="4.5"/><path d="M10.5 10.5L14 14"/>',
    close: '<path d="M4.5 4.5l7 7M11.5 4.5l-7 7"/>',
    chevron: '<path d="M4.5 6.5L8 10l3.5-3.5"/>',
    check: '<path d="M3.5 8.5l3 3 6-7"/>',
    fix: '<path vector-effect="non-scaling-stroke" transform="scale(0.6667)" d="M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z"/>',
    type: '<rect x="2.5" y="2.5" width="11" height="11" rx="2.5"/><path d="M5.5 5.75h5M8 5.75v4.75"/>',
    custom: '<path d="M8 1.75l5.5 3.1v6.3L8 14.25l-5.5-3.1v-6.3z"/><path d="M2.5 4.85L8 8l5.5-3.15M8 8v6.25"/>',
    expand: '<path d="M5 6l3-3 3 3M5 10l3 3 3-3"/>',
    collapse: '<path d="M5 2.5l3 3 3-3M5 13.5l3-3 3 3"/>',
    reload: '<path d="M13.25 8a5.25 5.25 0 1 1-1.54-3.71"/><path d="M13.25 2.25v3.5h-3.5"/>',
    copy: '<rect x="5.5" y="5.5" width="8" height="8" rx="1.75"/><path d="M10.5 5.5V4.25A1.75 1.75 0 0 0 8.75 2.5h-4.5A1.75 1.75 0 0 0 2.5 4.25v4.5c0 .97.78 1.75 1.75 1.75H5.5"/>',
    file: '<path d="M9 1.75H4.75c-.97 0-1.75.78-1.75 1.75v9c0 .97.78 1.75 1.75 1.75h6.5c.97 0 1.75-.78 1.75-1.75V5.75z"/><path d="M9 1.75v4h4"/>',
    external: '<path d="M9.5 2.5h4v4M13.5 2.5l-6 6"/><path d="M11.5 9.5v2.75c0 .69-.56 1.25-1.25 1.25h-6.5c-.69 0-1.25-.56-1.25-1.25v-6.5c0-.69.56-1.25 1.25-1.25H6.5"/>',
};

const TOTALS = counts(RULES);

const STATUS_OPTIONS = [
    { value: 'all', label: 'All', tone: 'var(--fg)' },
    { value: 'active', label: 'Active', tone: 'var(--active)', swatch: 'on', count: TOTALS.active, tip: 'Rules enabled by your current detekt settings and configuration.' },
    { value: 'inactive', label: 'Inactive', tone: 'var(--muted)', swatch: 'off', count: TOTALS.total - TOTALS.active, tip: 'Rules that are loaded but not enabled.' },
];

const ORIGIN_OPTIONS = [
    { value: 'all', label: 'All', tone: 'var(--fg)' },
    { value: 'builtin', label: 'Built-in', tone: 'var(--accent)', swatch: 'on', count: TOTALS.total - TOTALS.custom, tip: 'Rules that ship with detekt.' },
    { value: 'custom', label: 'Custom', tone: 'var(--custom)', swatch: 'on', count: TOTALS.custom, tip: 'Rules loaded from the plugin jars in the detekt settings.' },
];

const FLAGS = [
    { key: 'autoCorrect', label: 'Auto-correct', tip: 'Only rules whose findings detekt can fix automatically.', test: r => r.autoCorrect },
    { key: 'typeRes', label: 'Type resolution', tip: 'Only rules that need type resolution. These never report in the editor.', test: r => r.typeRes },
    { key: 'examples', label: 'Has examples', tip: 'Only rules that come with code examples.', test: r => r.examples.length > 0 },
    { key: 'configured', label: 'Configured', tip: 'Only rules with options set in your configuration files.', test: r => r.options.some(o => o.value != null) },
];

const TAGS = [
    {
        cls: 'custom',
        icon: 'custom',
        test: r => r.custom,
        hidden: () => state.origin === 'custom' || state.group === 'origin',
        tip: r => ['Custom rule', 'Loaded from ' + (r.jar || 'a plugin jar') + ', one of the plugin jars in the detekt settings.'],
    },
    {
        cls: 'fix',
        icon: 'fix',
        test: r => r.autoCorrect,
        tip: () => ['Auto-correct', 'detekt can fix findings of this rule. Run Refactor > AutoCorrect by detekt Rules.'],
    },
    {
        cls: 'type',
        icon: 'type',
        test: r => r.typeRes,
        tip: () => ['Needs type resolution', 'The editor analyzes files without type information, so this rule never reports here. It only runs in Gradle tasks with type resolution, such as detektMain.'],
    },
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
        for (const [name, value] of Object.entries(attrs)) {
            if (value == null || value === false) continue;
            if (name === 'class') el.className = value;
            else if (name === 'html') el.innerHTML = value;
            else if (name.startsWith('on')) el.addEventListener(name.slice(2), value);
            else el.setAttribute(name, value === true ? '' : value);
        }
    }
    for (const child of children.flat(Infinity)) {
        if (child == null || child === false) continue;
        el.appendChild(typeof child === 'string' ? document.createTextNode(child) : child);
    }
    return el;
}

function icon(name, extra) {
    const wrap = document.createElement('span');
    wrap.innerHTML = '<svg class="icon' + (extra ? ' ' + extra : '') + '" viewBox="0 0 16 16" aria-hidden="true">' + ICONS[name] + '</svg>';
    return wrap.firstChild;
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

function counts(list) {
    return {
        total: list.length,
        active: list.filter(r => r.active).length,
        custom: list.filter(r => r.custom).length,
    };
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
        matches(rule, term))
        .sort((SORTS[state.sort] || SORTS.name).cmp);
}

function filtersActive() {
    return Boolean(state.q || state.status !== 'all' || state.origin !== 'all' || state.flags.length);
}

function key(rule) {
    return rule.set + '/' + rule.id;
}

const tip = h('div', { class: 'tip', role: 'tooltip' });
document.body.appendChild(tip);
let tipTarget = null;
let tipTimer = 0;
let tipHiddenAt = 0;

function hideTip() {
    clearTimeout(tipTimer);
    if (tip.classList.contains('show')) tipHiddenAt = performance.now();
    tipTarget = null;
    tip.classList.remove('show');
}

function fillTip(target) {
    const body = target.dataset.tipBody;
    tip.innerHTML = '<strong>' + escapeHtml(target.dataset.tip) + '</strong>' + (body ? '<span>' + escapeHtml(body) + '</span>' : '');
}

function placeTip(target) {
    const rect = target.getBoundingClientRect();
    const width = tip.offsetWidth;
    const height = tip.offsetHeight;
    const below = rect.bottom + 8 + height <= window.innerHeight;
    const top = below ? rect.bottom + 8 : rect.top - 8 - height;
    const left = Math.min(Math.max(8, rect.left + rect.width / 2 - width / 2), window.innerWidth - width - 8);
    tip.style.left = left + 'px';
    tip.style.top = Math.max(8, top) + 'px';
    tip.style.setProperty('--tip-shift', below ? '-4px' : '4px');
}

function showTip(target) {
    fillTip(target);
    placeTip(target);
    tip.classList.add('show');
}

document.addEventListener('mouseover', e => {
    const target = e.target.closest ? e.target.closest('[data-tip]') : null;
    if (target === tipTarget) return;
    hideTip();
    if (!target) return;
    tipTarget = target;
    const warm = performance.now() - tipHiddenAt < 500;
    tipTimer = setTimeout(() => showTip(target), warm ? 0 : 450);
});
document.addEventListener('mousedown', hideTip, true);
document.addEventListener('scroll', hideTip, true);
document.documentElement.addEventListener('mouseleave', hideTip);
window.addEventListener('blur', hideTip);

function field(label, ...controls) {
    return h('div', { class: 'field' }, h('span', { class: 'field-label' }, label), controls);
}

function iconButton(name, title, body, onclick) {
    return h('button', { class: 'icon-btn', 'data-tip': title, 'data-tip-body': body, 'aria-label': title, onclick: onclick }, icon(name));
}

function segmented(name, options) {
    const thumb = h('span', { class: 'thumb' });
    const el = h('div', { class: 'segmented', role: 'radiogroup' }, thumb);
    const buttons = new Map();
    for (const option of options) {
        const button = h('button', {
            role: 'radio',
            'data-tip': option.tip ? option.label : null,
            'data-tip-body': option.tip,
            onclick: () => update({ [name]: option.value }),
        },
            option.swatch ? h('span', { class: 'swatch ' + option.swatch, style: '--sw: ' + option.tone }) : null,
            option.label,
            option.count != null ? h('span', { class: 'count' }, String(option.count)) : null);
        buttons.set(option.value, button);
        el.appendChild(button);
    }
    const segment = { name, el, thumb, buttons, options };
    ui.segments[name] = segment;
    if (window.ResizeObserver) new ResizeObserver(() => syncSegment(segment)).observe(el);
    return el;
}

function syncSegment(segment) {
    const option = segment.options.find(o => o.value === state[segment.name]) || segment.options[0];
    segment.el.style.setProperty('--tone', option.tone);
    for (const [value, button] of segment.buttons) {
        button.classList.toggle('on', value === option.value);
        button.setAttribute('aria-checked', String(value === option.value));
    }
    const selected = segment.buttons.get(option.value);
    segment.thumb.style.width = selected.offsetWidth + 'px';
    segment.thumb.style.transform = 'translateX(' + selected.offsetLeft + 'px)';
}

function chip(flag) {
    const button = h('button', {
        class: 'chip',
        'data-tip': flag.label,
        'data-tip-body': flag.tip,
        onclick: () => update({
            flags: state.flags.includes(flag.key) ? state.flags.filter(f => f !== flag.key) : state.flags.concat(flag.key),
        }),
    }, h('span', { class: 'box' }, icon('check')), flag.label);
    ui.chips[flag.key] = button;
    return button;
}

function select(label, name, options) {
    const el = h('select', { onchange: e => update({ [name]: e.target.value }) },
        Object.entries(options).map(([value, option]) => h('option', { value: value }, option.label)));
    ui.selects[name] = el;
    return field(label, h('span', { class: 'select' }, el, icon('chevron')));
}

function clearSearch() {
    state.q = '';
    ui.input.value = '';
    syncToolbar();
    renderResults(true);
    save();
}

function buildToolbar() {
    ui.input = h('input', {
        type: 'search',
        placeholder: 'Search ' + TOTALS.total + ' rules, options, classes',
        value: state.q,
        spellcheck: 'false',
        oninput: e => {
            state.q = e.target.value;
            syncToolbar();
            renderResults(false);
            save();
        },
        onkeydown: e => {
            if (e.key === 'Escape' && state.q) {
                e.preventDefault();
                e.stopPropagation();
                clearSearch();
            }
        },
    });
    const clear = iconButton('close', 'Clear search', null, () => {
        clearSearch();
        ui.input.focus();
    });
    clear.classList.add('clear');
    ui.search = h('div', { class: 'search' }, icon('search', 'lead'), ui.input, clear);
    ui.summary = h('span', { class: 'summary' });
    ui.reset = h('button', {
        class: 'reset',
        onclick: () => {
            ui.input.value = '';
            update({ q: '', status: 'all', origin: 'all', flags: [] });
        },
    }, icon('close'), 'Clear filters');
    ui.reload = iconButton('reload', 'Reload', 'Load the rules again from the current settings.', () => {
        ui.reload.classList.add('spin');
        send('reload', '');
    });
    ui.toolbar = h('header', { class: 'toolbar' },
        ui.search,
        h('div', { class: 'row' },
            field('Status', segmented('status', STATUS_OPTIONS)),
            field('Origin', segmented('origin', ORIGIN_OPTIONS))),
        h('div', { class: 'row' }, field('Only', FLAGS.map(chip))),
        h('div', { class: 'row spread' },
            h('div', { class: 'row' }, select('Group', 'group', GROUPS), select('Sort', 'sort', SORTS)),
            h('div', { class: 'row tight' },
                ui.summary,
                ui.reset,
                h('span', { class: 'tools' },
                    iconButton('expand', 'Expand all', 'Open every rule in the list.', () => setAllOpen(true)),
                    iconButton('collapse', 'Collapse all', 'Close every open rule.', () => setAllOpen(false)),
                    ui.reload))));
    return ui.toolbar;
}

function syncToolbar() {
    if (!ui.toolbar) return;
    Object.values(ui.segments).forEach(syncSegment);
    for (const flag of FLAGS) {
        const on = state.flags.includes(flag.key);
        ui.chips[flag.key].classList.toggle('on', on);
        ui.chips[flag.key].setAttribute('aria-pressed', String(on));
    }
    ui.selects.group.value = state.group;
    ui.selects.sort.value = state.sort;
    ui.search.classList.toggle('has-value', Boolean(state.q));
    ui.reset.hidden = !filtersActive();
}

function update(patch) {
    Object.assign(state, patch);
    syncToolbar();
    renderResults(true);
    save();
}

function setAllOpen(open) {
    if (open) {
        collapsedGroups.clear();
        document.querySelectorAll('.group.collapsed').forEach(group => group.classList.remove('collapsed'));
    }
    document.querySelectorAll('.rule').forEach(article => article.setOpen(open));
    save();
}

function tags(rule) {
    const shown = TAGS.filter(tag => tag.test(rule) && !(tag.hidden && tag.hidden()));
    if (!shown.length) return null;
    return h('span', { class: 'tags' }, shown.map(tag => {
        const [title, body] = tag.tip(rule);
        return h('span', { class: 'tag ' + tag.cls, 'data-tip': title, 'data-tip-body': body }, icon(tag.icon));
    }));
}

function section(title, ...content) {
    return h('section', null, h('h4', null, title), content);
}

function codeBlock(kind, label, code) {
    return h('div', { class: 'code ' + kind },
        h('div', { class: 'label' }, label),
        h('pre', { html: highlightKotlin(code) }));
}

function examplesSection(rule) {
    if (!rule.examples.length) return null;
    return section(rule.examples.length === 1 ? 'Example' : 'Examples',
        rule.examples.map(example => h('div', { class: 'example' },
            example.title ? h('div', { class: 'example-title' }, example.title) : null,
            h('div', { class: 'pair' },
                example.before != null ? codeBlock('bad', 'Noncompliant', example.before) : null,
                example.after != null ? codeBlock('good', 'Compliant', example.after) : null),
            example.note ? h('div', { class: 'note', html: inlineMarkup(example.note, '') }) : null)));
}

function optionsSection(rule, term) {
    if (!rule.options.length) return null;
    const described = rule.options.some(option => option.desc);
    return section('Configuration',
        h('table', { class: 'options' },
            h('thead', null, h('tr', null, h('th', null, 'Option'), h('th', null, 'Value'), described ? h('th', null, 'Description') : null)),
            h('tbody', null, rule.options.map(option => {
                const overridden = option.value != null && option.value !== option.def;
                const value = option.value != null ? option.value : option.def;
                return h('tr', null,
                    h('td', null, h('code', { html: highlightText(option.name, term) })),
                    h('td', { class: 'value' },
                        h('span', {
                            class: overridden ? 'overridden' : null,
                            'data-tip': overridden ? 'Set in your configuration' : null,
                            'data-tip-body': overridden && option.def != null ? 'Default is ' + option.def : null,
                        }, value != null ? value : '-')),
                    described ? h('td', { html: option.desc ? inlineMarkup(option.desc, term) : '' }) : null);
            }))));
}

function copyButton(text) {
    const button = iconButton('copy', 'Copy', text, () => {
        send('copy', text);
        button.classList.remove('copied');
        void button.offsetWidth;
        button.classList.add('copied');
        button.replaceChildren(icon('check'));
        button.dataset.tip = 'Copied';
        if (tipTarget === button) showTip(button);
        clearTimeout(button.resetTimer);
        button.resetTimer = setTimeout(() => {
            button.classList.remove('copied');
            button.replaceChildren(icon('copy'));
            button.dataset.tip = 'Copy';
            if (tipTarget === button) fillTip(button);
        }, 1400);
    });
    return button;
}

function actions(rule) {
    const ident = rule.cls || key(rule);
    return h('div', { class: 'actions' },
        rule.src ? h('button', { class: 'btn', 'data-tip': 'Open in editor', 'data-tip-body': rule.cls, onclick: () => send('source', rule.cls) }, icon('file'), rule.src) : null,
        rule.url ? h('button', { class: 'btn', 'data-tip': 'Open documentation', 'data-tip-body': rule.url, onclick: () => send('open', rule.url) }, icon('external'), 'Documentation') : null,
        h('span', { class: 'ident' }, h('span', { class: 'ident-text' }, ident), copyButton(ident)));
}

function details(rule, term) {
    return h('div', { class: 'details' },
        rule.rationale ? section('Details', paragraphs(rule.rationale, term)) : null,
        examplesSection(rule),
        optionsSection(rule, term),
        actions(rule));
}

function ruleView(rule, term, showSet) {
    const id = key(rule);
    const inner = h('div', { class: 'inner' });
    const article = h('article', { class: 'rule' + (rule.active ? '' : ' inactive') });
    let built = false;
    article.setOpen = open => {
        if (open && !built) {
            inner.appendChild(details(rule, term));
            built = true;
        }
        article.classList.toggle('open', open);
        if (open) openRules.add(id); else openRules.delete(id);
    };
    const head = h('div', {
        class: 'rule-head',
        onclick: () => {
            article.setOpen(!article.classList.contains('open'));
            save();
        },
    },
        icon('chevron', 'chev'),
        h('span', {
            class: 'dot' + (rule.active ? '' : ' off'),
            'data-tip': rule.active ? 'Active' : 'Inactive',
            'data-tip-body': rule.active ? 'Enabled by your current detekt settings and configuration.' : 'Not enabled by your current detekt settings and configuration.',
        }),
        h('h3', { html: highlightText(rule.id, term) }),
        showSet ? h('span', { class: 'set' }, rule.set) : null,
        tags(rule));
    article.append(head,
        h('div', { class: 'desc', html: rule.desc ? inlineMarkup(rule.desc, term) : '<span class="hint">No description.</span>' }),
        h('div', { class: 'collapse' }, inner));
    if (openRules.has(id)) article.setOpen(true);
    return article;
}

function groupView(name, rules, term) {
    const id = state.group + ':' + name;
    const c = counts(rules);
    const jars = Array.from(new Set(rules.map(r => r.jar).filter(Boolean)));
    const group = h('section', { class: 'group' + (collapsedGroups.has(id) && !term ? ' collapsed' : '') });
    const head = h('div', {
        class: 'group-head',
        onclick: () => {
            const collapsed = group.classList.toggle('collapsed');
            if (collapsed) collapsedGroups.add(id); else collapsedGroups.delete(id);
            save();
        },
    },
        h('h2', null, name),
        h('span', {
            class: 'meta',
            'data-tip': c.total === 1 ? '1 rule' : c.total + ' rules',
            'data-tip-body': jars.length ? 'From ' + jars.join(', ') : null,
        }, c.active + ' of ' + c.total + ' active'),
        icon('chevron', 'chev'));
    group.append(head, h('div', { class: 'collapse' }, h('div', { class: 'inner' }, rules.map(rule => ruleView(rule, term, state.group !== 'set')))));
    return group;
}

function resultsView(shown) {
    const term = state.q.trim();
    const main = h('main', { id: 'results' });
    if (!RULES.length) {
        main.appendChild(h('p', { class: 'empty' }, MESSAGE || 'No detekt rules loaded.'));
        return main;
    }
    if (!shown.length) {
        main.appendChild(h('div', { class: 'empty' },
            h('p', null, 'No rules match these filters.'),
            filtersActive() ? h('button', { class: 'btn', onclick: () => ui.reset.click() }, icon('close'), 'Clear filters') : null));
        return main;
    }
    const group = GROUPS[state.group];
    if (!group || state.group === 'none') {
        main.appendChild(h('div', { class: 'flat' }, shown.map(rule => ruleView(rule, term, true))));
        return main;
    }
    const groups = new Map();
    for (const rule of shown) {
        const name = group.key(rule);
        if (!groups.has(name)) groups.set(name, []);
        groups.get(name).push(rule);
    }
    Array.from(groups.keys())
        .sort((a, b) => a.localeCompare(b, undefined, { sensitivity: 'base' }))
        .forEach(name => main.appendChild(groupView(name, groups.get(name), term)));
    return main;
}

function renderResults(animate) {
    hideTip();
    const shown = visibleRules();
    const main = resultsView(shown);
    if (animate) main.classList.add('enter');
    const current = document.getElementById('results');
    if (current) current.replaceWith(main); else app.appendChild(main);
    if (ui.summary) {
        ui.summary.innerHTML = shown.length === TOTALS.total
            ? '<strong>' + shown.length + '</strong> rules'
            : '<strong>' + shown.length + '</strong> of ' + TOTALS.total + ' rules';
    }
}

document.addEventListener('keydown', e => {
    if (!ui.input || e.target === ui.input) return;
    if ((e.key === 'f' && (e.ctrlKey || e.metaKey)) || e.key === '/') {
        e.preventDefault();
        ui.input.focus();
        ui.input.select();
    }
});

window.addEventListener('scroll', () => {
    if (ui.toolbar) ui.toolbar.classList.toggle('scrolled', window.scrollY > 0);
    save();
}, { passive: true });

if (RULES.length) app.appendChild(buildToolbar());
renderResults(false);
syncToolbar();
requestAnimationFrame(() => requestAnimationFrame(() => {
    Object.values(ui.segments).forEach(segment => segment.el.classList.add('ready'));
}));
if (typeof INITIAL?.scroll === 'number') window.scrollTo(0, INITIAL.scroll);
