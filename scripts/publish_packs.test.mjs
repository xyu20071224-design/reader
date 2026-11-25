import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {createDictionary, inspectPack, publish} from './publish_packs.mjs';

async function fixture(fn) {
  const dir=fs.mkdtempSync(path.join(os.tmpdir(),'lr-publisher-'));
  try {
    const database=path.join(dir,'test.sqlite'),license=path.join(dir,'LICENSE'),out=path.join(dir,'ecdict.lrpack');
    fs.writeFileSync(database,'archive fixture');fs.writeFileSync(license,'MIT fixture');
    await createDictionary({database,license,out});
    await fn({dir,database,license,out,root:path.join(dir,'public')});
  } finally {
    const target=path.resolve(dir),tempRoot=path.resolve(os.tmpdir());
    if(!target.startsWith(tempRoot+path.sep) || !path.basename(target).startsWith('lr-publisher-')) throw new Error('Unsafe test cleanup');
    fs.rmSync(target,{recursive:true,force:true});
  }
}

test('publishes verified immutable archive before independent catalogue',()=>fixture(async ({root,out})=>{
  assert.equal(inspectPack(out).packId,'ecdict');
  const result=await publish({root,packs:[out]});
  const pack=result.packs[0];
  assert.equal(result.formatVersion,1);
  assert.match(pack.urls[0],/^https:\/\/62\.234\.28\.97:8443\/packs\/files\/[0-9a-f]{64}\/ecdict\.lrpack$/);
  const target=path.join(root,'packs','files',pack.sha256,'ecdict.lrpack');
  assert.deepEqual(fs.readFileSync(target),fs.readFileSync(out));
  assert.equal(JSON.parse(fs.readFileSync(path.join(root,'packs/index.json'))).packs.length,1);
  await publish({root,packs:[out]});
  assert.equal(JSON.parse(fs.readFileSync(path.join(root,'packs/index.json'))).packs.length,1);
}));

test('same version cannot silently replace installed content',()=>fixture(async ({root,out,database,license})=>{
  await publish({root,packs:[out]});
  const before=fs.readFileSync(path.join(root,'packs/index.json'));
  fs.writeFileSync(database,'changed archive fixture');await createDictionary({database,license,out});
  await assert.rejects(publish({root,packs:[out]}),/相同资源版本/);
  assert.deepEqual(fs.readFileSync(path.join(root,'packs/index.json')),before);
}));

test('bad source, duplicate package and untrusted URL do not publish a catalogue',()=>fixture(async ({root,out})=>{
  await assert.rejects(publish({root,packs:[out],baseUrl:'http://example.com'}));
  await assert.rejects(publish({root,packs:[out,out]}),/相同 packId/);
  assert.equal(fs.existsSync(path.join(root,'packs/index.json')),false);
  fs.writeFileSync(out,'not a ZIP');
  await assert.rejects(publish({root,packs:[out]}));
  assert.equal(fs.existsSync(path.join(root,'packs/index.json')),false);
}));

test('modified already-published object is detected before catalogue replacement',()=>fixture(async ({root,out})=>{
  const result=await publish({root,packs:[out]});
  fs.writeFileSync(path.join(root,'packs/files',result.packs[0].sha256,'ecdict.lrpack'),'tampered');
  const before=fs.readFileSync(path.join(root,'packs/index.json'));
  await assert.rejects(publish({root,packs:[out]}),/已发布文件被修改/);
  assert.deepEqual(fs.readFileSync(path.join(root,'packs/index.json')),before);
}));

test('new package version replaces catalogue entry while preserving the old download',()=>fixture(async ({root,out,database,license})=>{
  const first=await publish({root,packs:[out]});
  const oldObject=path.join(root,'packs/files',first.packs[0].sha256,'ecdict.lrpack');
  await createDictionary({database,license,out,version:'1.1.0'});
  const next=await publish({root,packs:[out]});
  assert.equal(next.packs.length,1);
  assert.equal(next.packs[0].version,'1.1.0');
  assert.equal(fs.existsSync(oldObject),true);
  assert.notEqual(next.packs[0].sha256,first.packs[0].sha256);
}));

// Stored ZIP writer for independently malformed archives; production always uses JDK ZipFile.
const sha = data => crypto.createHash('sha256').update(data).digest('hex');
function crc32(data) { let n=0xffffffff;for(const b of data){n^=b;for(let i=0;i<8;i++)n=(n>>>1)^((n&1)?0xedb88320:0);}return (n^0xffffffff)>>>0; }
function zip(entries) {
  const files=[],central=[];let offset=0;
  for(const [name,value,crcOverride] of entries){const data=Buffer.isBuffer(value)?value:Buffer.from(value),label=Buffer.from(name),crc=crcOverride??crc32(data);
    const h=Buffer.alloc(30);h.writeUInt32LE(0x04034b50);h.writeUInt16LE(20,4);h.writeUInt16LE(0x800,6);h.writeUInt32LE(crc,14);h.writeUInt32LE(data.length,18);h.writeUInt32LE(data.length,22);h.writeUInt16LE(label.length,26);
    files.push(h,label,data);const c=Buffer.alloc(46);c.writeUInt32LE(0x02014b50);c.writeUInt16LE(20,4);c.writeUInt16LE(20,6);c.writeUInt16LE(0x800,8);c.writeUInt32LE(crc,16);c.writeUInt32LE(data.length,20);c.writeUInt32LE(data.length,24);c.writeUInt16LE(label.length,28);c.writeUInt32LE(offset,42);central.push(c,label);offset+=h.length+label.length+data.length;
  }
  const body=Buffer.concat(central),end=Buffer.alloc(22);end.writeUInt32LE(0x06054b50);end.writeUInt16LE(entries.length,8);end.writeUInt16LE(entries.length,10);end.writeUInt32LE(body.length,12);end.writeUInt32LE(offset,16);return Buffer.concat([...files,body,end]);
}
function manifest(id='fixture',version='1.0.0',payloads={'dictionary/data.sqlite':Buffer.from('contract fixture')}) {
  return {packId:id,type:'dictionary',version,nameEn:'Fixture',schemaVersion:1,minAppVersion:1,files:Object.entries(payloads).map(([path,b])=>({path,bytes:Buffer.byteLength(b),sha256:sha(b)})),dictionary:{entryFile:'dictionary/data.sqlite'}};
}
function rawPack(dir,m,payloads={'dictionary/data.sqlite':Buffer.from('contract fixture')},name='fixture.lrpack') {
  const out=path.join(dir,name);fs.writeFileSync(out,zip([['manifest.json',typeof m==='string'?m:JSON.stringify(m)],...Object.entries(payloads)]));return out;
}
async function temporary(fn) {const dir=fs.mkdtempSync(path.join(os.tmpdir(),'lr-publisher-'));try{await fn(dir);}finally{fs.rmSync(dir,{recursive:true,force:true});}}
const indexBytes=root=>fs.readFileSync(path.join(root,'packs/index.json'));

import crypto from 'node:crypto';
import {spawn} from 'node:child_process';
import {AUDIO_PIPELINE_VERSION,strictJson,validateCatalog} from './publish_packs.mjs';

test('archive guard rejects normalized aliases, traversal, duplicate names, file-directory collisions and CRC corruption',()=>temporary(async dir=>{
  const m=manifest();
  for(const unsafe of ['../escape','a/../b','a/./b','a//b','/absolute','a\\b','C:relative','a\u0000b']) {
    const bad=rawPack(dir,manifest('fixture','1.0.0',{[unsafe]:Buffer.from('x')}),{[unsafe]:Buffer.from('x')});assert.throws(()=>inspectPack(bad),/Unsafe ZIP path|invalid|Invalid/i);
  }
  for(const entries of [
    [['manifest.json',JSON.stringify(m)],['manifest.json',JSON.stringify(m)]],
    [['manifest.json',JSON.stringify(m)],['a','x'],['a/b','y']],
    [['manifest.json',JSON.stringify(m)],['dictionary/data.sqlite','contract fixture',0]]
  ]){const file=path.join(dir,'bad.lrpack');fs.writeFileSync(file,zip(entries));assert.throws(()=>inspectPack(file));}
}));

test('manifest validation follows identity, payload references, names, exact files and integer contracts',()=>temporary(async dir=>{
  const cases=[m=>m.packId='Upper',m=>m.packId='a'.repeat(65),m=>m.packId=undefined,m=>m.version='v'.repeat(33),m=>m.minAppVersion=0.5,m=>delete m.nameEn,m=>m.dictionary.entryFile='missing',m=>m.files.push(m.files[0]),m=>m.files[0].bytes=0.5,m=>m.files[0].path='manifest.json',m=>m.files[0].sha256='0'.repeat(64)];
  for(const change of cases){const m=manifest();change(m);assert.throws(()=>inspectPack(rawPack(dir,m)));}
  const body=JSON.stringify(manifest()).replace('"packId":"fixture"','"packId":"first","packId":"fixture"');assert.throws(()=>inspectPack(rawPack(dir,body)),/Duplicate JSON key/);
  assert.throws(()=>strictJson('{"a":1,"a":2}'),/Duplicate/);
}));

test('voice and audio payloads accept valid current contracts and reject unusable references or chapter trees',()=>temporary(async dir=>{
  const payloads={'sample.mp3':Buffer.from('sample')};const v={...manifest('voice-one','1.0.0',payloads),type:'voice',voice:{voices:[{key:'one',mode:'clone',sample:'sample.mp3'}]}};delete v.dictionary;
  assert.equal(inspectPack(rawPack(dir,v,payloads)).type,'voice');v.voice.voices[0].sample='missing';assert.throws(()=>inspectPack(rawPack(dir,v,payloads)),/reference/);
  const engine='mimo',voice='mimo-clone:room',segment='e'+sha(engine).slice(0,8)+'~v'+AUDIO_PIPELINE_VERSION+'~'+voice,relative=segment+'/s0-0.mp3',file='audio/0/'+relative;
  const sounds={[file]:Buffer.from('audio fixture')},a={...manifest('audio-one','1.0.0',sounds),type:'audio',audio:{bookId:'book',engineTag:engine,voice,pipelineVersion:AUDIO_PIPELINE_VERSION,chapters:[{index:0,files:1,treeSha256:sha(relative+':'+sha(sounds[file]))}]}};delete a.dictionary;
  assert.equal(inspectPack(rawPack(dir,a,sounds)).type,'audio');a.audio.chapters[0].treeSha256='0'.repeat(64);assert.throws(()=>inspectPack(rawPack(dir,a,sounds)),/tree/);
  const kotlin=fs.readFileSync(new URL('../src/shared/src/main/java/com/linguareader/shared/tts/TtsPipelineContract.kt',import.meta.url),'utf8');assert.equal(Number(kotlin.match(/const val VERSION = (\d+)/)[1]),AUDIO_PIPELINE_VERSION);
}));

test('bundle validation inspects nested member bytes and exact identity',()=>temporary(async dir=>{
  const member=fs.readFileSync(rawPack(dir,manifest('inner'))),payloads={'members/inner.lrpack':member};
  const m={...manifest('collection','1.0.0',payloads),type:'bundle',bundle:{members:[{packId:'inner',type:'dictionary',version:'1.0.0',path:'members/inner.lrpack'}]}};delete m.dictionary;
  assert.equal(inspectPack(rawPack(dir,m,payloads,'bundle.lrpack')).type,'bundle');m.bundle.members[0].packId='wrong';assert.throws(()=>inspectPack(rawPack(dir,m,payloads,'bundle.lrpack')),/identity mismatch/);
}));

test('version history rejects changed v1 after a successful v2 upgrade and idempotent retries keep index bytes',()=>temporary(async dir=>{
  const root=path.join(dir,'public'),one=rawPack(dir,manifest('one'),undefined,'one.lrpack');await publish({root,packs:[one]});const first=indexBytes(root);await publish({root,packs:[one]});assert.deepEqual(indexBytes(root),first);
  rawPack(dir,manifest('one','2.0.0'),undefined,'one.lrpack');await publish({root,packs:[one]});const before=indexBytes(root);
  const changed={'dictionary/data.sqlite':Buffer.from('changed')};rawPack(dir,manifest('one','1.0.0',changed),changed,'one.lrpack');await assert.rejects(publish({root,packs:[one]}),/相同资源版本/);assert.deepEqual(indexBytes(root),before);
}));

test('batch failure, unrelated corrupt object and failed index rename preserve the existing catalogue',()=>temporary(async dir=>{
  const root=path.join(dir,'public'),one=rawPack(dir,manifest('one'),undefined,'one.lrpack'),two=rawPack(dir,manifest('two'),undefined,'two.lrpack');const first=await publish({root,packs:[one]});const before=indexBytes(root);
  const bad=path.join(dir,'bad.lrpack');fs.writeFileSync(bad,'not zip');await assert.rejects(publish({root,packs:[two,bad]}));assert.deepEqual(indexBytes(root),before);
  const rename=fs.renameSync;try{fs.renameSync=(a,b)=>{if(b===path.join(fs.realpathSync(root),'packs/index.json'))throw new Error('injected index failure');return rename(a,b);};await assert.rejects(publish({root,packs:[two]}),/injected/);}finally{fs.renameSync=rename;}
  assert.deepEqual(indexBytes(root),before);assert.equal(fs.existsSync(path.join(root,'packs/.publish.lock')),false);
  const next=await publish({root,packs:[two]});assert.deepEqual(next.packs.map(p=>p.packId),['one','two']);assert.equal(next.publishedVersions['one@1.0.0'].sha256,first.packs[0].sha256);
  fs.writeFileSync(path.join(root,'packs/files',first.packs[0].sha256,'one.lrpack'),'bad');const stable=indexBytes(root);await assert.rejects(publish({root,packs:[two]}),/已发布文件被修改/);assert.deepEqual(indexBytes(root),stable);
}));

test('malformed old catalogue, symlinked object roots and abandoned locks fail closed',()=>temporary(async dir=>{
  const root=path.join(dir,'public'),pack=rawPack(dir,manifest());await publish({root,packs:[pack]});const index=path.join(root,'packs/index.json');const before=fs.readFileSync(index);
  fs.writeFileSync(index,'{"formatVersion":1,"formatVersion":1,"packs":[]}');const malformed=fs.readFileSync(index);await assert.rejects(publish({root,packs:[pack]}),/Duplicate/);assert.deepEqual(fs.readFileSync(index),malformed);fs.writeFileSync(index,before);
  const lock=path.join(root,'packs/.publish.lock');fs.writeFileSync(lock,'crashed owner');await assert.rejects(publish({root,packs:[pack]}),/locked/);assert.equal(fs.readFileSync(lock,'utf8'),'crashed owner');fs.unlinkSync(lock);
  const linked=path.join(dir,'linked');fs.mkdirSync(path.join(linked,'packs'),{recursive:true});fs.symlinkSync(path.join(root,'packs/files'),path.join(linked,'packs/files'),'dir');await assert.rejects(publish({root:linked,packs:[pack]}),/Unsafe directory/);assert.equal(fs.existsSync(path.join(linked,'packs/index.json')),false);
}));

test('cross-process publishers do not lose updates; a rejected contender can retry safely',()=>temporary(async dir=>{
  const root=path.join(dir,'public'),one=rawPack(dir,manifest('one'),undefined,'one.lrpack'),two=rawPack(dir,manifest('two'),undefined,'two.lrpack');const module=new URL('./publish_packs.mjs',import.meta.url).href;
  const child=pack=>{const p=spawn(process.execPath,['--input-type=module','-e',`import {publish} from ${JSON.stringify(module)};await publish(${JSON.stringify({root,packs:[pack]})});`],{env:process.env});let output='';p.stdout.on('data',x=>output+=x);p.stderr.on('data',x=>output+=x);return {p,done:new Promise(resolve=>p.on('close',code=>resolve({code,output})))};};
  const first=child(one),until=Date.now()+10000;while(!fs.existsSync(path.join(root,'packs/.publish.lock'))&&Date.now()<until)await new Promise(r=>setTimeout(r,10));assert.equal(fs.existsSync(path.join(root,'packs/.publish.lock')),true);
  const contender=await child(two).done;assert.notEqual(contender.code,0);assert.match(contender.output,/locked/);assert.equal((await first.done).code,0);
  await publish({root,packs:[two]});assert.deepEqual(JSON.parse(indexBytes(root)).packs.map(p=>p.packId),['one','two']);
}));
