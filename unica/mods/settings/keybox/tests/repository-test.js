// Copyright (c) 2026 Rezoss
// SPDX-License-Identifier: GPL-3.0-or-later
const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const html = fs.readFileSync(process.argv[2], 'utf8');
const script = html.match(/<script>([\s\S]*?)<\/script>/)[1];
const deliveries = [], handshakes = [];
let listener, tick;
const frame = {contentWindow: {postMessage: (...args) => handshakes.push(args)}};
const window = {addEventListener: (type, callback) => { assert.equal(type, 'message'); listener = callback; }};
vm.runInNewContext(script, {window, document: {getElementById: () => frame},
    setInterval: callback => { tick = callback; return 1; }, clearInterval: () => {}});
listener({source: null, data: 'connect', ports: [{postMessage: value => deliveries.push(JSON.parse(value))}]});
frame.onload(); tick();
assert.equal(JSON.stringify(handshakes), JSON.stringify([[{type: 'handshake'}, 'https://keybox.kowx712.cc']]));
const send = (origin, source, data) => listener({origin, source, data, ports: []});
send('https://attacker.invalid', frame.contentWindow, {type: 'download', url: 'https://example.org/keybox'});
send('https://keybox.kowx712.cc', {}, {type: 'download', url: 'https://example.org/keybox'});
send('https://keybox.kowx712.cc', frame.contentWindow, {type: 'download', url: {}});
assert.equal(deliveries.length, 0);
send('https://keybox.kowx712.cc', frame.contentWindow, {type: 'download', url: 'https://example.org/keybox'});
assert.deepEqual(deliveries, [{type: 'download', url: 'https://example.org/keybox'}]);
send('https://keybox.kowx712.cc', frame.contentWindow, {type: 'error'});
assert.equal(deliveries[1].type, 'error');
assert(!html.includes('addJavascriptInterface'));
console.log('Repository protocol tests passed (native port, handshake, origin/source checks, malformed selection and error)');
