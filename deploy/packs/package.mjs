import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import crypto from 'node:crypto';
import {spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
import {DEFAULT_BASE_URL,digest,inspectPack,strictJson,validateCatalog} from '../../scripts/publish_packs.mjs';
const here=path.dirname(fileURLToPath(import.meta.url));
export async function packageDeployment({site,out,baseUrl=DEFAULT_BASE_URL}) {
  const base=new URL(baseUrl);if(base.protocol!=='https:'||base.username||base.password||base.search||base.hash||base.pathname!=='/')throw new Error('Expected an HTTPS origin');
  const index=path.join(path.resolve(site),'packs/index.json');if(fs.lstatSync(index).isSymbolicLink()||fs.statSync(index).size>1024**2)throw new Error('Invalid source catalogue');
  const catalog=validateCatalog(strictJson(fs.readFileSync(index,'utf8')));
  fs.mkdirSync(path.dirname(path.resolve(out)),{recursive:true});
  const stage=fs.mkdtempSync(path.join(os.tmpdir(),'lr-deployment-')),temporary=path.resolve(out)+'.'+crypto.randomUUID()+'.tmp';
  try {
    const copy=(source,relative)=>{const destination=path.join(stage,relative);fs.mkdirSync(path.dirname(destination),{recursive:true});fs.copyFileSync(source,destination,fs.constants.COPYFILE_EXCL);};
    for(const name of ['activate.py','install-ubuntu.sh','install_config.py'])copy(path.join(here,name),name);
    fs.mkdirSync(path.join(stage,'site/packs'),{recursive:true});fs.writeFileSync(path.join(stage,'site/packs/index.json'),JSON.stringify(catalog,null,2)+'\n');
    for(const p of catalog.packs) {
      if(p.urls[0]!==`${base.origin}/packs/files/${p.sha256}/${p.name}`)throw new Error('Catalogue target differs from --base-url');
      const source=path.join(path.resolve(site),'packs/files',p.sha256,p.name);
      if(!fs.lstatSync(source).isFile()||fs.statSync(source).size!==p.bytes||await digest(source)!==p.sha256)throw new Error('Invalid source object');
      const m=inspectPack(source);if(['packId','type','version','minAppVersion'].some(k=>m[k]!==p[k]))throw new Error('Archive/catalogue identity mismatch');
      copy(source,`site/packs/files/${p.sha256}/${p.name}`);
    }
    fs.writeFileSync(path.join(stage,'deployment.json'),JSON.stringify({formatVersion:1,baseUrl:base.origin,createdAt:new Date().toISOString()},null,2)+'\n');
    const files=[];function walk(dir){for(const entry of fs.readdirSync(dir,{withFileTypes:true})){const f=path.join(dir,entry.name);if(entry.isDirectory())walk(f);else if(entry.isFile())files.push(f);else throw new Error('Invalid staged file');}}walk(stage);
    const sums=[];for(const file of files.sort())sums.push(`${await digest(file)}  ${path.relative(stage,file).split(path.sep).join('/')}`);
    fs.writeFileSync(path.join(stage,'SHA256SUMS'),sums.join('\n')+'\n');
    const result=spawnSync('tar',['-czf',temporary,'-C',stage,'.'],{encoding:'utf8'});if(result.error||result.status!==0)throw new Error(result.error?.message||result.stderr||'tar failed');
    const fd=fs.openSync(temporary,'r');try{fs.fsyncSync(fd);}finally{fs.closeSync(fd);}fs.renameSync(temporary,path.resolve(out));
    return {archive:path.resolve(out),sha256:await digest(path.resolve(out)),baseUrl:base.origin,packs:catalog.packs.length};
  } finally {fs.rmSync(stage,{recursive:true,force:true});if(fs.existsSync(temporary))fs.unlinkSync(temporary);}
}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url)) {
  try{const options={},args=process.argv.slice(2);for(let i=0;i<args.length;i+=2){const key=args[i]?.slice(2);if(!args[i]?.startsWith('--')||!['site','out','base-url'].includes(key)||!args[i+1]||key in options)throw new Error('Expected --site/--out/--base-url values');options[key]=args[i+1];}if(!options.site||!options.out)throw new Error('Missing --site or --out');console.log(JSON.stringify(await packageDeployment({site:options.site,out:options.out,baseUrl:options['base-url']})));}catch(e){console.error(e.message);process.exitCode=1;}
}
