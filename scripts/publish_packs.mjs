import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import crypto from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
export const DEFAULT_BASE_URL = 'https://62.234.28.97:8443';
export const AUDIO_PIPELINE_VERSION = 3; // Keep in sync with TtsPipelineContract.VERSION (tested).
const packIdPattern = /^[a-z0-9][a-z0-9._-]{0,63}$/;
const versionPattern = /^[A-Za-z0-9][A-Za-z0-9._-]{0,31}$/;
const namePattern = /^[A-Za-z0-9][A-Za-z0-9._-]{0,150}\.lrpack$/;
const shaPattern = /^[0-9a-f]{64}$/;
const MiB = 1024 ** 2;
const limits = {dictionary:[500*MiB,10000,1024*MiB],voice:[500*MiB,10000,1024*MiB],audio:[2048*MiB,200000,4096*MiB],bundle:[2048*MiB,200000,4096*MiB]};
function requireThat(ok, message) { if (!ok) throw new Error(message); }
const integer = (n, minimum = 0, maximum = Number.MAX_SAFE_INTEGER) => Number.isSafeInteger(n) && n >= minimum && n <= maximum;
const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);
const id = value => typeof value === 'string' && packIdPattern.test(value);
const version = value => typeof value === 'string' && versionPattern.test(value);
const type = value => typeof value === 'string' && Object.hasOwn(limits,value);
const text = value => typeof value === 'string' && value.trim().length > 0;
const hashText = value => crypto.createHash('sha256').update(value).digest('hex');

// JSON.parse alone silently accepts duplicate fields, unlike the installation parser.
export function strictJson(body) {
  let i=0;
  const ws=()=>{while (/\s/.test(body[i] || '\0')) i++;};
  function string() {
    const start=i++;
    while(i<body.length) { const c=body[i++]; if(c==='\\') i++; else if(c==='"') return JSON.parse(body.slice(start,i)); }
    throw new Error('Unterminated JSON string');
  }
  function value(depth) {
    requireThat(depth<64,'JSON nesting too deep'); ws(); const c=body[i];
    if(c==='"') {string();return;}
    if(c==='{') {
      i++;ws();const seen=new Set();if(body[i]==='}') {i++;return;}
      while(true) {ws();requireThat(body[i]==='"','Invalid JSON key');const key=string();requireThat(!seen.has(key),'Duplicate JSON key: '+key);seen.add(key);ws();requireThat(body[i++]===':','Invalid JSON object');value(depth+1);ws();const end=body[i++];if(end==='}')return;requireThat(end===',','Invalid JSON object');}
    }
    if(c==='[') {i++;ws();if(body[i]===']'){i++;return;}while(true){value(depth+1);ws();const end=body[i++];if(end===']')return;requireThat(end===',','Invalid JSON array');}}
    const token=/^(?:true|false|null|-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?)/.exec(body.slice(i));
    requireThat(token,'Invalid JSON value');i+=token[0].length;
  }
  value(0);ws();requireThat(i===body.length,'Trailing JSON content');return JSON.parse(body);
}
function safePath(value) {
  requireThat(text(value) && value===value.trim() && !/[\\\x00-\x1f\x7f]/.test(value) && !value.startsWith('/') && !/^[A-Za-z]:/.test(value) && value.split('/').every(s=>s && s!=='.' && s!=='..'), 'Unsafe payload path: '+value);
  return value;
}
function baseOrigin(value) {
  const u=new URL(value);
  requireThat(u.protocol==='https:' && !u.username && !u.password && !u.hash && !u.search && u.pathname==='/', 'base-url must be an HTTPS origin');
  return u.origin;
}
function archive(...args) {
  const java=process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME,'bin',process.platform==='win32'?'java.exe':'java') : 'java';
  const result=spawnSync(java,['-Dfile.encoding=UTF-8',path.join(scriptDir,'PackArchive.java'),...args.map(String)],{encoding:'utf8',maxBuffer:100*MiB});
  if(result.error || result.status!==0) throw new Error(result.error?.message || result.stderr || 'PackArchive failed');
  return result.stdout;
}
export async function digest(file) {
  const h=crypto.createHash('sha256');for await(const part of fs.createReadStream(file))h.update(part);return h.digest('hex');
}
function regular(file) { requireThat(fs.lstatSync(file).isFile(),'Expected a regular file: '+file); }
function safeDirectory(directory) {
  const absolute=path.resolve(directory);
  let current=path.parse(absolute).root;
  for(const part of absolute.slice(current.length).split(path.sep).filter(Boolean)) {
    current=path.join(current,part);
    if(!fs.existsSync(current))fs.mkdirSync(current);
    requireThat(fs.lstatSync(current).isDirectory() && !fs.lstatSync(current).isSymbolicLink(),'Unsafe directory: '+current);
  }
  return absolute;
}
function syncDirectory(directory) {
  if(process.platform==='win32')return; // Directory fsync is not available through Node on Windows.
  const fd=fs.openSync(directory,'r');try{fs.fsyncSync(fd);}finally{fs.closeSync(fd);}
}
function writeDurable(file, body) { const fd=fs.openSync(file,'wx',0o644);try{fs.writeFileSync(fd,body);fs.fsyncSync(fd);}finally{fs.closeSync(fd);} }
function validateManifest(m, actual) {
  requireThat(object(m) && id(m.packId) && version(m.version) && type(m.type) && m.schemaVersion===1 && integer(m.minAppVersion,0,2147483647),'Invalid pack identity, type, schema or compatibility');
  requireThat(text(m.nameZh)||text(m.nameEn)||text(m.name),'Pack has no display name');
  requireThat(Array.isArray(m.files)&&m.files.length>0,'Pack has no files');
  const declared=new Map();
  for(const f of m.files) {
    requireThat(object(f),'Invalid files entry');safePath(f.path);
    requireThat(f.path!=='manifest.json' && !declared.has(f.path) && integer(f.bytes) && shaPattern.test(f.sha256),'Invalid or duplicate declared file: '+f.path);
    declared.set(f.path,f);
    const a=actual.get(f.path);requireThat(a && a.bytes===f.bytes && a.sha256===f.sha256,'File missing or checksum mismatch: '+f.path);
  }
  requireThat(declared.size===actual.size,'Pack has undeclared files');
  const ref=p=>requireThat(declared.has(safePath(p)),'Payload reference not declared: '+p);
  if(m.type==='dictionary') {requireThat(object(m.dictionary),'Missing dictionary payload');ref(m.dictionary.entryFile);}
  if(m.type==='voice') {
    requireThat(object(m.voice)&&Array.isArray(m.voice.voices)&&m.voice.voices.length>0,'Missing voices');const keys=new Set();
    for(const v of m.voice.voices) {requireThat(object(v)&&id(v.key)&&!keys.has(v.key),'Invalid or duplicate voice');keys.add(v.key);const mode=v.mode||'metadata';requireThat(['clone','metadata'].includes(mode),'Invalid voice mode');if(mode==='clone'||v.sample)ref(v.sample);}
  }
  if(m.type==='bundle') {
    requireThat(object(m.bundle)&&Array.isArray(m.bundle.members)&&m.bundle.members.length>0,'Empty bundle');const ids=new Set(),paths=new Set();
    for(const member of m.bundle.members) {requireThat(object(member)&&['dictionary','voice','audio'].includes(member.type)&&id(member.packId)&&version(member.version)&&!ids.has(member.packId)&&!paths.has(member.path),'Invalid or duplicate bundle member');ref(member.path);requireThat(member.path.endsWith('.lrpack'),'Bundle member is not an lrpack');ids.add(member.packId);paths.add(member.path);}
  }
  if(m.type==='audio') {
    const a=m.audio;requireThat(object(a)&&text(a.bookId)&&text(a.engineTag)&&text(a.voice)&&a.pipelineVersion===AUDIO_PIPELINE_VERSION&&Array.isArray(a.chapters)&&a.chapters.length>0,'Invalid audio payload/pipeline');
    const voice=(!a.voice || ['.','..'].includes(a.voice) || /[\\/\0]/.test(a.voice))?'h-'+hashText(a.voice).slice(0,16):a.voice;
    const segment='e'+hashText(a.engineTag).slice(0,8)+'~v'+a.pipelineVersion+'~'+voice;
    const grouped=new Map();
    for(const [p,f] of declared)if(p.startsWith('audio/')) {
      const parts=p.split('/');requireThat(parts.length===4&&/^\d+$/.test(parts[1])&&parts[2]===segment&&/^s\d+-\d+\.mp3$/.test(parts[3]),'Audio path does not match engine/voice/pipeline');
      const index=Number(parts[1]);if(!grouped.has(index))grouped.set(index,[]);grouped.get(index).push(parts.slice(2).join('/')+':'+f.sha256);
    }
    const seen=new Set();for(const c of a.chapters){requireThat(object(c)&&integer(c.index,0,2147483647)&&integer(c.files,1)&&shaPattern.test(c.treeSha256)&&!seen.has(c.index),'Invalid or duplicate audio chapter');seen.add(c.index);const rows=grouped.get(c.index);requireThat(rows&&rows.length===c.files&&hashText(rows.sort().join('\n'))===c.treeSha256,'Audio chapter tree mismatch');}
    requireThat(seen.size===grouped.size,'Undeclared audio chapter');
  }
}
export function inspectPack(file, depth=0) {
  regular(file);requireThat(fs.statSync(file).size>0&&fs.statSync(file).size<=2048*MiB,'Pack source too large or empty');
  const raw=Buffer.from(archive('manifest',file).trim(),'base64').toString('utf8');const first=strictJson(raw);
  requireThat(object(first)&&type(first.type),'Invalid pack type');const [maxSource,maxEntries,maxUnzip]=limits[first.type];
  requireThat(fs.statSync(file).size<=maxSource,'Pack exceeds type size limit');
  const [meta,...lines]=archive('inspect',file,maxEntries,maxUnzip).trim().split(/\r?\n/);
  requireThat(Buffer.from(meta,'base64').toString('utf8')===raw,'Source manifest changed during inspection');
  const actual=new Map(lines.filter(Boolean).map(line=>{const [p,bytes,sha256]=line.split('\t');return [Buffer.from(p,'base64').toString('utf8'),{bytes:Number(bytes),sha256}];}));
  validateManifest(first,actual);
  if(first.type==='bundle') {
    requireThat(depth===0,'Nested bundles are unsupported');const temporary=fs.mkdtempSync(path.join(os.tmpdir(),'lr-members-'));
    try { for(const [i,member] of first.bundle.members.entries()){const target=path.join(temporary,String(i)+'.lrpack');archive('member',file,member.path,target);const inner=inspectPack(target,depth+1);requireThat(inner.type===member.type&&inner.packId===member.packId&&inner.version===member.version,'Bundle member identity mismatch');} }
    finally{fs.rmSync(temporary,{recursive:true,force:true});}
  }
  return first;
}
export function validateCatalog(catalog) {
  requireThat(object(catalog)&&catalog.formatVersion===1&&Array.isArray(catalog.packs)&&catalog.packs.length<=1000,'Invalid catalogue');
  const identities=new Set(),ids=new Set();
  for(const p of catalog.packs) {
    requireThat(object(p)&&id(p.packId)&&version(p.version)&&type(p.type)&&namePattern.test(p.name)&&integer(p.bytes,1,limits[p.type][0])&&shaPattern.test(p.sha256)&&integer(p.minAppVersion,0,2147483647),'Invalid catalogue entry');
    requireThat(!ids.has(p.packId),'Duplicate catalogue packId');ids.add(p.packId);identities.add(p.packId+'@'+p.version);
    requireThat(Array.isArray(p.urls)&&p.urls.length>=1&&p.urls.length<=5,'Invalid mirrors');
    for(const value of p.urls){requireThat(typeof value==='string'&&!/[\x00-\x20\x7f]/.test(value),'Invalid URL');const u=new URL(value);requireThat(u.protocol==='https:'&&!u.username&&!u.password&&!u.hash,'Invalid HTTPS URL');}
  }
  const history=catalog.publishedVersions??{};requireThat(object(history),'Invalid version history');
  for(const [identity,value] of Object.entries(history)){const split=identity.split('@');requireThat(split.length===2&&id(split[0])&&version(split[1])&&object(value)&&shaPattern.test(value.sha256)&&type(value.type)&&integer(value.bytes,1,limits[value.type][0]),'Invalid version history identity');}
  requireThat(Buffer.byteLength(JSON.stringify(catalog))<=MiB,'Catalogue exceeds client size limit');return catalog;
}
async function verifiedObject(directory, entry) {
  const file=path.join(safeDirectory(path.join(directory,'files',entry.sha256)),entry.name);regular(file);
  requireThat(fs.statSync(file).size===entry.bytes&&await digest(file)===entry.sha256,'已发布文件被修改： '+file);return file;
}
export async function publish({root,baseUrl=DEFAULT_BASE_URL,packs}) {
  const origin=baseOrigin(baseUrl);requireThat(Array.isArray(packs)&&packs.length>0,'No packs supplied');
  fs.mkdirSync(path.resolve(root),{recursive:true});
  const directory=safeDirectory(path.join(fs.realpathSync(root),'packs'));const lock=path.join(directory,'.publish.lock');const owner={token:crypto.randomUUID(),pid:process.pid,host:os.hostname(),startedAt:new Date().toISOString()};
  let lockFd;try{lockFd=fs.openSync(lock,'wx',0o600);}catch(e){if(e.code==='EEXIST')throw new Error('Publication locked; inspect .publish.lock and recover manually after confirming its owner stopped');throw e;}
  try {
    fs.writeFileSync(lockFd,JSON.stringify(owner)+'\n');fs.fsyncSync(lockFd);
    const index=path.join(directory,'index.json');let oldText=null,catalog;
    if(fs.existsSync(index)){regular(index);requireThat(fs.statSync(index).size<=MiB,'Existing catalogue too large');oldText=fs.readFileSync(index,'utf8');catalog=validateCatalog(strictJson(oldText));}
    else catalog={formatVersion:1,packs:[]};
    const history={...(catalog.publishedVersions??{})};
    function remember(p){const id=p.packId+'@'+p.version,identity={sha256:p.sha256,bytes:p.bytes,type:p.type};const prior=history[id];requireThat(!prior||(prior.sha256===identity.sha256&&prior.bytes===identity.bytes&&prior.type===identity.type),'相同资源版本不能替换内容： '+id);history[id]=identity;}
    for(const old of catalog.packs){remember(old);await verifiedObject(directory,old);}
    const staging=fs.mkdtempSync(path.join(directory,'.publish-'));
    try {
      const entries=[];
      for(const [i,source] of packs.entries()) {
        const name=path.basename(source);requireThat(namePattern.test(name),'Invalid lrpack filename');regular(source);
        const snapshot=path.join(staging,i+'.lrpack');fs.copyFileSync(source,snapshot,fs.constants.COPYFILE_EXCL);
        const m=inspectPack(snapshot),sha256=await digest(snapshot),bytes=fs.statSync(snapshot).size;
        const p={packId:m.packId,version:m.version,type:m.type,name,minAppVersion:m.minAppVersion,bytes,sha256,urls:[`${origin}/packs/files/${sha256}/${name}`]};remember(p);entries.push({snapshot,p});
      }
      requireThat(new Set(entries.map(x=>x.p.packId)).size===entries.length,'一次发布不能包含相同 packId');
      const result={...catalog,publishedVersions:history,packs:catalog.packs.filter(p=>!entries.some(x=>x.p.packId===p.packId)).concat(entries.map(x=>x.p)).sort((a,b)=>a.packId.localeCompare(b.packId))};
      validateCatalog(result);
      for(const {snapshot,p} of entries) {
        const targetDir=safeDirectory(path.join(directory,'files',p.sha256)),target=path.join(targetDir,p.name);
        if(fs.existsSync(target)){await verifiedObject(directory,p);continue;}
        const temp=path.join(targetDir,'.'+crypto.randomUUID()+'.tmp');
        try {fs.copyFileSync(snapshot,temp,fs.constants.COPYFILE_EXCL);const fd=fs.openSync(temp,'r');try{fs.fsyncSync(fd);}finally{fs.closeSync(fd);}requireThat(await digest(temp)===p.sha256,'Copy checksum mismatch');fs.renameSync(temp,target);syncDirectory(targetDir);}
        finally{if(fs.existsSync(temp))fs.unlinkSync(temp);}
      }
      if(JSON.stringify({...catalog,updatedAt:undefined})===JSON.stringify({...result,updatedAt:undefined}))return catalog;
      result.updatedAt=new Date().toISOString();const body=JSON.stringify(result,null,2)+'\n';requireThat(Buffer.byteLength(body)<=MiB,'Catalogue exceeds client size limit');
      const temporary=path.join(directory,'.index-'+crypto.randomUUID()+'.tmp');
      try {writeDurable(temporary,body);fs.renameSync(temporary,index);syncDirectory(directory);}finally{if(fs.existsSync(temporary))fs.unlinkSync(temporary);}
      return result;
    } finally {fs.rmSync(staging,{recursive:true,force:true});}
  } finally {
    fs.closeSync(lockFd);
    if(fs.existsSync(lock)&&fs.readFileSync(lock,'utf8')===JSON.stringify(owner)+'\n')fs.unlinkSync(lock);
  }
}
export async function createDictionary({database,license,out,version='1.0.0'}) {
  requireThat(versionPattern.test(version),'Invalid pack version');regular(database);regular(license);
  const manifest={packId:'ecdict',type:'dictionary',version,nameZh:'ECDICT 离线英汉词典',nameEn:'ECDICT offline dictionary',schemaVersion:1,minAppVersion:1,
    files:[{path:'dictionary/ecdict.sqlite',bytes:fs.statSync(database).size,sha256:await digest(database)},{path:'LICENSE-ECDICT.txt',bytes:fs.statSync(license).size,sha256:await digest(license)}],dictionary:{entryFile:'dictionary/ecdict.sqlite',source:'ECDICT / skywind3000',license:'MIT'}};
  fs.mkdirSync(path.dirname(path.resolve(out)),{recursive:true});out=path.join(fs.realpathSync(path.dirname(path.resolve(out))),path.basename(out));const meta=out+'.'+crypto.randomUUID()+'.json',temp=out+'.'+crypto.randomUUID()+'.tmp';
  try{writeDurable(meta,JSON.stringify(manifest));archive('dictionary',temp,database,license,meta);inspectPack(temp);fs.renameSync(temp,out);syncDirectory(path.dirname(path.resolve(out)));}
  finally{for(const f of [meta,temp])if(fs.existsSync(f))fs.unlinkSync(f);}return out;
}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url)) {
  try {
    const [command,...args]=process.argv.slice(2),options={packs:[]};const allowed={dictionary:new Set(['database','license','out','version']),publish:new Set(['root','base-url','pack'])};requireThat(allowed[command],'Expected dictionary or publish');
    for(let i=0;i<args.length;i+=2){const flag=args[i];requireThat(flag?.startsWith('--')&&args[i+1]&&allowed[command].has(flag.slice(2)),'Invalid command option');const key=flag.slice(2);if(key==='pack')options.packs.push(path.resolve(args[i+1]));else{requireThat(!Object.hasOwn(options,key),'Duplicate command option');options[key]=args[i+1];}}
    if(command==='dictionary'){const assets=path.resolve(scriptDir,'../src/app/src/main/assets/dictionary'),version=options.version??'1.0.0';console.log(await createDictionary({database:options.database??path.join(assets,'ecdict.sqlite'),license:options.license??path.join(assets,'LICENSE-ECDICT.txt'),out:options.out??path.resolve(scriptDir,`../artifacts/ecdict-${version}.lrpack`),version}));}
    else{requireThat(options.root,'Missing --root');const c=await publish({root:options.root,baseUrl:options['base-url']??DEFAULT_BASE_URL,packs:options.packs});console.log(`Published ${c.packs.length} packs: ${path.resolve(options.root,'packs/index.json')}`);}
  }catch(e){console.error(e.message);process.exitCode=1;}
}
