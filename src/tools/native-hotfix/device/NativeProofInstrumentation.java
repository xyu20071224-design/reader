package com.linguareader.hotfixproof;

import android.app.Instrumentation;
import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import java.io.*;
import java.lang.reflect.*;
import java.security.MessageDigest;
import java.util.concurrent.*;

/** Independent Java-only release diagnostic: no dependency on obfuscated Kotlin/AndroidX names. */
public final class NativeProofInstrumentation extends Instrumentation {
    private Bundle arguments;
    private Context context;
    private Class<?> runtimeType;
    private Object runtime;
    @Override public void onCreate(Bundle args) { arguments=args; start(); }
    @Override public void onStart() {
        Bundle output=new Bundle();
        try {
            context=getTargetContext();
            runtimeType=context.getClassLoader().loadClass("com.linguareader.app.hotfix.NativeHotfixRuntime");
            runtime=runtimeType.getConstructor(Context.class).newInstance(context);
            String action=arguments.getString("action");
            if ("prepareSignedNativePatchWithoutInstallingAnApk".equals(action)) {
                apply(new File(context.getExternalFilesDir(null),"fixture.lrpatch"),1);
                require((Boolean)get(snapshot(),"getRestartRequired"),"Patch not ready for restart");
            } else if ("interruptedResultCallbackRecoversFromLoadedSignedMetadata".equals(action)) {
                File stateFile=new File(context.getFilesDir(),"hotfix/reader-25/status.json");
                ByteArrayOutputStream bytes=new ByteArrayOutputStream();
                try(InputStream input=new FileInputStream(stateFile)){byte[] b=new byte[4096];int n;while((n=input.read(b))!=-1)bytes.write(b,0,n);}
                org.json.JSONObject pending=new org.json.JSONObject(bytes.toString("UTF-8")).put("phase","applying").put("highest",0);
                android.util.AtomicFile atomic=new android.util.AtomicFile(stateFile);
                FileOutputStream stream=atomic.startWrite();stream.write(pending.toString().getBytes("UTF-8"));atomic.finishWrite(stream);
                Object state=snapshot();
                require(((Integer)get(state,"getHighestRevision"))==1,"Lost signed revision high water");
                require(((Integer)get(state,"getLoadedRevision"))==1,"Signed native patch not loaded");
                require(!(Boolean)get(state,"getRestartRequired"),"Already loaded patch should not restart again");
                bytes.reset();
                try(InputStream input=new FileInputStream(stateFile)){byte[] b=new byte[4096];int n;while((n=input.read(b))!=-1)bytes.write(b,0,n);}
                require("ready".equals(new org.json.JSONObject(bytes.toString("UTF-8")).getString("phase")),"Interrupted callback state was not recovered");
            } else if ("rollbackRetainsRevisionHighWaterAndRestoresBaseOnReopen".equals(action)) {
                runtimeType.getMethod("rollback").invoke(runtime);
                Object state=snapshot();
                require(((Integer)get(state,"getHighestRevision"))==1,"Lost revision high water");
                require(((Integer)get(state,"getLoadedRevision"))==1,"Live native code unloaded prematurely");
                require((Boolean)get(state,"getRestartRequired"),"Rollback restart missing");
            } else if ("tamperedPatchCannotBecomeActive".equals(action)) {
                File bad=new File(context.getCacheDir(),"tampered.lrpatch");
                try(FileOutputStream f=new FileOutputStream(bad)){f.write("not a signed patch".getBytes("UTF-8"));}
                reject(bad,2,"");
            } else if ("validArchiveWithWrongCertificateIsRejected".equals(action)) {
                reject(new File(context.getExternalFilesDir(null),"wrong-cert.lrpatch"),2,"signature/base validation failed");
            } else if ("validSignatureWithWrongBaseIsRejected".equals(action)) {
                reject(new File(context.getExternalFilesDir(null),"wrong-base.lrpatch"),2,"signature/base validation failed");
            } else if ("unsignedDiscoveryCannotForgeSignedRevision".equals(action)) {
                reject(new File(context.getExternalFilesDir(null),"wrong-revision.lrpatch"),2,"Signed patch metadata mismatch");
            } else if ("rolledBackRevisionCannotBeReapplied".equals(action)) {
                reject(new File(context.getExternalFilesDir(null),"wrong-revision.lrpatch"),1,"already applied or rolled back");
            } else throw new AssertionError("Unknown diagnostic action "+action);
            output.putBoolean("proofPassed",true);output.putString("stream","PASS "+action+"\n");
            finish(Activity.RESULT_OK,output);
        } catch(Throwable e) {
            StringWriter trace=new StringWriter();e.printStackTrace(new PrintWriter(trace));
            output.putBoolean("proofPassed",false);output.putString("stream",trace.toString());
            finish(Activity.RESULT_CANCELED,output);
        }
    }
    private Object snapshot() throws Exception {return runtimeType.getMethod("snapshot").invoke(runtime);}
    private static Object get(Object state,String getter) throws Exception {return state.getClass().getMethod(getter).invoke(state);}
    private static void require(boolean yes,String message){if(!yes)throw new AssertionError(message);}
    private void reject(File file,int revision,String text) throws Throwable {
        try { apply(file,revision);throw new AssertionError("Invalid patch accepted: "+file); }
        catch(IllegalArgumentException e){require(e.getMessage()!=null && e.getMessage().contains(text),"Wrong rejection: "+e);}
    }
    private static Throwable failure(Object result) throws Exception {
        if(result==null)return null;
        for(Field f:result.getClass().getDeclaredFields()) {
            if(!Modifier.isStatic(f.getModifiers()) && Throwable.class.isAssignableFrom(f.getType())) {
                f.setAccessible(true);return (Throwable)f.get(result);
            }
        }
        return null;
    }
    private void apply(File file,int revision) throws Throwable {
        require(file.isFile(),"Missing signed fixture "+file);
        Method apply=null;
        for(Method m:runtimeType.getMethods())if(m.getName().equals("apply") && m.getParameterTypes().length==3)apply=m;
        require(apply!=null,"Missing native apply ABI");
        Class<?>[] types=apply.getParameterTypes();
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(InputStream input=new FileInputStream(file)) {byte[] b=new byte[65536];int n;while((n=input.read(b))!=-1)digest.update(b,0,n);}
        StringBuilder hex=new StringBuilder();for(byte b:digest.digest())hex.append(String.format("%02x",b&255));
        Object info=types[1].getConstructor(String.class,int.class,int.class,String.class,String.class,String.class,long.class,String.class)
            .newInstance("reader-25",25,revision,file.getName(),"https://github.com/xyu20071224-design/reader/releases/download/test/"+file.getName(),hex.toString(),file.length(),"Release device diagnostic");
        Class<?> emptyType=context.getClassLoader().loadClass(arguments.getString("emptyContext"));
        Object empty=null;
        for(Field f:emptyType.getDeclaredFields()) if(Modifier.isStatic(f.getModifiers()) && f.getType()==emptyType){f.setAccessible(true);empty=f.get(null);break;}
        require(empty!=null,"Missing Kotlin empty context singleton");
        final Object coroutineContext=empty;
        final CountDownLatch completion=new CountDownLatch(1);
        final Throwable[] error=new Throwable[1];
        Object continuation=Proxy.newProxyInstance(types[2].getClassLoader(),new Class<?>[]{types[2]},(proxy,method,args)->{
            if(method.getDeclaringClass()==Object.class){
                if(method.getName().equals("toString"))return "Native release diagnostic continuation";
                if(method.getName().equals("hashCode"))return System.identityHashCode(proxy);
                if(method.getName().equals("equals"))return proxy==args[0];
            }
            if(method.getParameterTypes().length==0)return coroutineContext;
            try{error[0]=failure(args[0]);}catch(Throwable e){error[0]=e;}finally{completion.countDown();}
            return null;
        });
        Object result;
        try{result=apply.invoke(runtime,file,info,continuation);}catch(InvocationTargetException e){throw e.getCause();}
        if(result instanceof Enum && ((Enum<?>)result).name().equals("COROUTINE_SUSPENDED")) {
            require(completion.await(130,TimeUnit.SECONDS),"Native apply callback timed out");
            if(error[0]!=null)throw error[0];
        } else {Throwable e=failure(result);if(e!=null)throw e;}
    }
}
