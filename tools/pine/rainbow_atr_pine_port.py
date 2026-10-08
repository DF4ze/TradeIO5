# Port Python INDEPENDANT du pine rainbow_dca_v4_atr_moon.pine (verification de parite du moteur Java
# RainbowAtrEngine). Usage :
#   python rainbow_atr_pine_port.py export-csv <btcusdt_klines_d1_full.json> <dir>   -> <dir>/d1.csv (t,h,l,c ; aussi lu par RainbowAtrRegimeBench)
#   python rainbow_atr_pine_port.py parity <dir>   -> <dir>/parity_cases.csv + parity_py.csv ; comparer avec RainbowAtrParityMain (parity_java.csv)
import sys, random, math
def load(p):
    T=[];H=[];L=[];C=[]
    for ln in open(p):
        t,h,l,c=ln.strip().split(','); T.append(int(t));H.append(float(h));L.append(float(l));C.append(float(c))
    return T,H,L,C
nan=float('nan')
def sma(C,p):
    o=[nan]*len(C)
    for i in range(p-1,len(C)): o[i]=sum(C[i-p+1:i+1])/p
    return o
def atr(H,L,C,p):
    n=len(C); tr=[H[0]-L[0]]+[max(H[i]-L[i],abs(H[i]-C[i-1]),abs(L[i]-C[i-1])) for i in range(1,n)]
    o=[nan]*n; o[p-1]=sum(tr[:p])/p
    for i in range(p,n): o[i]=(o[i-1]*(p-1)+tr[i])/p
    return o
def run(D,P,s,e):
    T,H,L,C=D; n=len(C)
    ath=[];a=None
    for i in range(n):
        a=H[i] if a is None else max(a,H[i]); ath.append(a)
    moonMode=[];moonEntry=[];moonExit=[]
    run_=False;peak=None
    for i in range(n):
        en=ex=False
        newAth = i>0 and C[i]>ath[i-1]
        if P['moonOn'] and (not run_) and newAth:
            run_=True;peak=H[i];en=True
        elif run_:
            peak=max(peak if peak is not None else H[i],H[i])
            if C[i]<=peak*(1-P['mtrail']/100):
                run_=False;ex=True;peak=None
        moonMode.append(run_);moonEntry.append(en);moonExit.append(ex)
    SM=sma(C,P['sma']);AT=atr(H,L,C,P['atr'])
    pos=cb=real=inv=sp=0.0; res=0.0
    bArm=sArm=lock=False; low=high=None; bd=sd=cd=0
    zb=tb=sl=ms=0
    for i in range(s,e+1):
        cl=C[i]; sm=SM[i]; at=AT[i]
        if math.isnan(sm) or math.isnan(at): continue
        eb=sm-at*P['d2']; zlow=sm-at*P['d1']; zh1=sm+at*P['u1']; zh2=sm+at*P['u2']; eh=sm+at*P['u3']
        zone='EB' if cl<eb else 'X2' if cl<zlow else 'X1' if cl<zh1 else 'X05' if cl<zh2 else 'NB' if cl<eh else 'EH'
        up=1+P['tb']/100; dn=1-P['ts']/100
        cas=P['cd'] if P['cdOn'] else 0
        mm=moonMode[i]
        athD= 1-cl/ath[i] if ath[i]>0 else 0.0
        tB=min(max(athD/(P['refBuy']/100),0),1); tS=min(max(athD/(P['refSell']/100),0),1)
        bf = P['bmin']+(P['bmax']-P['bmin'])*tB if (P['athOn'] and not mm) else 1.0
        sf = P['smax']+(P['smin']-P['smax'])*tS if (P['athOn'] and not mm) else 1.0
        sold=False
        if P['ratchet'] and mm and not moonEntry[i]: res=max(res,pos*P['reserve']/100)
        if moonEntry[i]: res=pos*P['reserve']/100
        if moonExit[i]:
            q=min(res,pos)*P['mstop']/100
            if q>0:
                pr=q*cl; co=cb*q/pos; real+=pr-co; cb-=co; pos-=q; sp+=pr; sold=True; ms+=1
            res=0.0
        if moonExit[i]:
            sArm=False;high=None;sd=0;cd=cas
        elif sArm:
            sd+=1
            if P['sellMode']=='T': trig = cl<eh or cl<high*dn
            elif P['sellMode']=='I': trig = cl<eh or cl<zh2
            else: trig = sd>=P['fd']
            if trig:
                req=pos*min(1.0,P['sf']*sf); sellable=max(pos-res,0.0); q=min(req,sellable)
                if q>0:
                    pr=q*cl; co=cb*q/pos; real+=pr-co; cb-=co; pos-=q; sp+=pr; sl+=1
                    if P['block']:
                        lock=True; sold=True; bArm=False; low=None; bd=0
                sArm=False;high=None;sd=0;cd=cas
            else: high=max(high,cl)
        elif zone=='EH' and (cd==0 or P['allowSell']):
            sArm=True;high=cl;sd=0
        if P['block'] and lock and (not sold) and cd==0 and cl<eb: lock=False
        bm=None; trg=False
        if (not sArm) and cd==0 and (not lock):
            if bArm:
                bd+=1
                if P['buyMode']=='T': t_= cl>eb or cl>low*up
                elif P['buyMode']=='I': t_= cl>eb
                else: t_= bd>=P['fd']
                if t_: bm=3.0;trg=True;bArm=False;low=None;bd=0
                else: low=min(low,cl)
            elif zone=='EB':
                bArm=True;low=cl;bd=0
            else:
                im={'X2':2.0,'X1':1.0,'X05':0.5}.get(zone)
                if im is not None and im>0: bm=im
        if bm is not None:
            iv=1.0*bf*bm; pos+=iv/cl; cb+=iv; inv+=iv
            if trg: tb+=1
            else: zb+=1
        if cd>0: cd-=1
    lp=C[e]
    return [inv,sp,pos*lp,real,cb,pos,zb,tb,sl,ms]
def rnd(rng,D):
    modes='TIF'
    P=dict(sma=rng.choice([10,14,20,26,36,50]),atr=rng.choice([7,14,21,28]),
      d2=rng.choice([1,2,3,4,6]),d1=rng.choice([0.5,1,2]),u1=rng.choice([0,0.5,1,2]),u2=rng.choice([0,1,2,3]),u3=rng.choice([2,3,4,6]),
      buyMode=rng.choice(modes),sellMode=rng.choice(modes),tb=rng.choice([2,3,5,10]),ts=rng.choice([2,3,5,10]),
      cd=rng.choice([0,1,3,7,12]),fd=rng.choice([3,5,10,15]),sf=rng.choice([.05,.1,.2,.5,1.0]),
      allowSell=rng.choice([False,True]),cdOn=rng.choice([True,False]),block=rng.choice([True,False]),
      athOn=rng.choice([True,False]),refBuy=rng.choice([30,60]),refSell=rng.choice([15,30,45]),bmin=rng.choice([0.5,1]),bmax=rng.choice([1,2,3]),smax=rng.choice([1,2,3]),smin=rng.choice([0.25,0.5,1]),
      moonOn=rng.choice([True,False]),reserve=rng.choice([0,25,50,75]),ratchet=rng.choice([False,True]),mtrail=rng.choice([10,15,30]),mstop=rng.choice([50,100]))
    P['u2']=max(P['u2'],P['u1']); P['u3']=max(P['u3'],P['u2']); P['d2']=max(P['d2'],P['d1'])
    return P
KEYS=['sma','atr','d2','d1','u1','u2','u3','buyMode','sellMode','tb','ts','cd','fd','sf','allowSell','cdOn','block','athOn','refBuy','refSell','bmin','bmax','smax','smin','moonOn','reserve','ratchet','mtrail','mstop']
if __name__=='__main__' and sys.argv[1]=='export-csv':
    import json
    d=json.load(open(sys.argv[2]))
    with open(sys.argv[3]+'/d1.csv','w') as f:
        for k in d: f.write(f"{k['t']},{k['h']},{k['l']},{k['c']}\n")
    sys.exit(0)
if __name__=='__main__':
    sys.argv.pop(1)
    base=sys.argv[1]; D=load(base+'/d1.csv'); n=len(D[2]); rng=random.Random(42)
    cases=open(base+'/parity_cases.csv','w'); out=open(base+'/parity_py.csv','w')
    for k in range(60):
        P=rnd(rng,D); s=rng.randint(80,n-300); e=s+rng.choice([90,180,270,400]); e=min(e,n-1)
        cases.write(','.join(str(P[x]) for x in KEYS)+f",{s},{e}\n")
        r=run(D,P,s,e); out.write(','.join(f"{v:.6f}" if isinstance(v,float) else str(v) for v in r)+'\n')
