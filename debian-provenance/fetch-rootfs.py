from pathlib import Path
from hashlib import sha256
import json,subprocess,sys,tarfile
import urllib.parse
H=Path(__file__).resolve().parent
registry='https://registry-1.docker.io/v2/library/debian'
TAG='trixie-20261123-slim'
accept='application/vnd.oci.image.index.v1+json, application/vnd.docker.distribution.manifest.list.v2+json, application/vnd.oci.image.manifest.v1+json, application/vnd.docker.distribution.manifest.v2+json'
def curl(url,*args):
 return subprocess.check_output(['curl','--silent','--show-error','--fail','--location','--max-time','120',*args,url])
auth='https://auth.docker.io/token?'+urllib.parse.urlencode(dict(service='registry.docker.io',scope='repository:library/debian:pull'))
token=json.loads(curl(auth))['token']
header=('.-H'.lstrip('.'),'Authorization: Bearer '+token)
def get(path,dig=None,dest=None):
 u=registry+'/'+path
 headers=(*header,'-H','Accept: '+accept)
 if dest:
  curl(u,*headers,'-o',str(dest))
  got='sha256:'+sha256(dest.read_bytes()).hexdigest()
  assert got==dig,(got,dig)
  return
 b=curl(u,*headers)
 got='sha256:'+sha256(b).hexdigest()
 assert dig is None or got==dig,(got,dig)
 return json.loads(b),got
idx,idh=get('manifests/'+TAG)
print('index',idh,flush=True)
all=[]
for arch in ('amd64','arm64'):
 match=[a for a in idx['manifests'] if a.get('platform',{}).get('architecture')==arch and a['platform']['os']=='linux']
 assert len(match)==1
 digest=match[0]['digest'];m,_=get('manifests/'+digest,digest)
 config,config_hash=get('blobs/'+m['config']['digest'],m['config']['digest'])
 assert config['os']=='linux' and config['architecture']==arch
 [lay]=m['layers'];assert lay['mediaType'].endswith(('.gzip','+gzip'))
 f=H/('debian13-'+arch+'-rootfs.tar.gz')
 get('blobs/'+lay['digest'],lay['digest'],f)
 assert f.stat().st_size==lay['size']
 result=dict(architecture=arch,image_tag=TAG,index=idh,manifest=digest,config=config_hash,sha256=lay['digest'].split(':')[1],bytes=lay['size'],file=f.name)
 with tarfile.open(f,'r:gz') as tf:
  osr=[n for n in tf.getnames() if n.removeprefix('./')=='usr/lib/os-release']
  assert len(osr)==1 and b'VERSION_CODENAME=trixie' in tf.extractfile(osr[0]).read()
 all.append(result)
 print(result,flush=True)
(H/'oci-receipts.json').write_text(json.dumps(all,indent=2)+'\n')
