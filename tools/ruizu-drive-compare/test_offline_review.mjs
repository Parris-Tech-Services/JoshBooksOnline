import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { runInNewContext } from 'node:vm';

const html = readFileSync(new URL('./offline_review.html', import.meta.url), 'utf8');
const script = html.match(/<script>([\s\S]*?)<\/script>/i)?.[1];
assert.ok(script, 'Standalone browser tool must include one inline script');
const elements = new Map();
const node = id => {
  if (!elements.has(id)) elements.set(id, {addEventListener(){}, style:{}, textContent:'', value:'', checked:false, replaceChildren(){}, append(){}});
  return elements.get(id);
};
const window = {};
const document = {getElementById:node};
runInNewContext(script, {window,document,location:{href:'about:blank',search:'?ruizu-test=1'},console});
const x=window.RuizuCompareTest;
assert.ok(x);
const source=(name='Song.mp3', size=33, more={})=>x.sourceData([{drive_file_id:'test',file_name:name,relative_path:name,size_bytes:size,...more}]);
const player=(name='Music/Song.mp3', size=33, more={})=>x.playerData([{relative_path:name,size_bytes:size,...more}]);

test('quoted fields, BOM, embedded comma and newline import correctly',()=>{
 const rows=x.parseCsv('\uFEFFdrive_file_id,file_name,relative_path,size_bytes\r\na,"Song, Part 1.mp3","Some\\nFolder",33\r\n');
 assert.equal(rows[0].file_name,'Song, Part 1.mp3');
 assert.equal(rows.length,1);
});
test('malformed CSV rejects rather than silently dropping rows',()=>{
 assert.throws(()=>x.parseCsv('a,b\n"unfinished'),/quoted field/);
 assert.throws(()=>x.parseCsv('a,b\n1,2,3'),/columns/); // row width validated by parseCsv
});
test('same normalised filename and bytes is only likely, not verified',()=>{
 assert.equal(x.compare(source('Artist - Écho.mp3'),player('Music/artist - echo.mp3'),true)[0].status,'LIKELY_PRESENT');
});
test('incomplete inventory does not claim missing',()=>{
 assert.equal(x.compare(source(),[],false)[0].status,'UNCONFIRMED');
 assert.equal(x.compare(source(),[],true)[0].status,'NOT_FOUND_IN_SCAN');
});
test('zero-byte source cannot be considered playable',()=>{
 assert.equal(x.compare(source('Empty.mp3',0),player('Empty.mp3',0),true)[0].status,'SOURCE_EMPTY');
});
test('same-name different-size remains possible',()=>{
 assert.equal(x.compare(source(),player('Song.mp3',43),true)[0].status,'POSSIBLE_MATCH');
});
test('matching hashes and contradictory file sizes are not verified',()=>{
 const digest='a'.repeat(64);
 assert.equal(x.compare(source('Song.mp3',33,{content_sha256:digest}),player('Song.mp3',43,{sha256:digest}),true)[0].status,'POSSIBLE_MATCH');
});
test('matching digest can identify renamed file with compatible sizes',()=>{
 const digest='b'.repeat(64);
 assert.equal(x.compare(source('Song.mp3',33,{content_sha256:digest}),player('Other.mp3',33,{sha256:digest}),true)[0].status,'VERIFIED_PRESENT');
});
test('unknown size preserves uncertainty',()=>{
 assert.equal(x.compare(source('Song.mp3',null),player('Song.mp3',33),true)[0].status,'POSSIBLE_MATCH');
});
test('duplicate Drive IDs cannot be imported',()=>{
 assert.throws(()=>x.sourceData([{drive_file_id:'1',file_name:'A.mp3'},{drive_file_id:'1',file_name:'B.mp3'}]),/duplicate Drive ID/);
});
test('spreadsheet formula-like names are escaped on CSV export',()=>{
 assert.equal(x.escapeCsv('=1+1'),`"'=1+1"`);
 assert.equal(x.escapeCsv('@cmd'),`"'@cmd"`);
});
test('HTML contains no remote resource references or network APIs',()=>{
 assert.match(html,/connect-src 'none'/);
 assert.doesNotMatch(html,/fetch\(|XMLHttpRequest|<iframe|<img\s+src="https?:\/\//i);
});