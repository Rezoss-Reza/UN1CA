// SPDX-License-Identifier: GPL-3.0-or-later
package io.mesalabs.rezoss.poweron;

final class ScheduleEngineTest {
    static int checks;
    static void check(boolean condition) { checks++;if(!condition)throw new AssertionError("Check "+checks); }
    interface Action { void run() throws Exception; }
    static void rejects(Action action) throws Exception {
        try {action.run();throw new AssertionError("Expected failure");}catch(AssertionError error){throw error;}
        catch(Exception expected){checks++;}
    }
    static final class Fake implements ScheduleEngine.Ports {
        long time=1792310400L,offset=1780000000L,lastProgram=-1;
        int programs,stores,shutdowns,arms,failStore;
        boolean failProgram,failArm,denyShutdown;
        ScheduleEngine.State state=new ScheduleEngine.State(0,0,0,5,"",false,"");
        ScheduleEngine engine=new ScheduleEngine(this);
        public ScheduleEngine.State load(){return state;}
        public void store(ScheduleEngine.State next) throws Exception {
            stores++;if(stores==failStore)throw new Exception("Disk failure");state=next;
        }
        public long now(){return time;}
        public int boot(){return 5;}
        public long[] snapshot(){return new long[]{time,time-offset};}
        public void program(long on)throws Exception{programs++;if(failProgram)throw new Exception("Driver failure");lastProgram=on;}
        public void checkShutdown()throws Exception{if(denyShutdown)throw new Exception("No shutdown permission");}
        public void checkExactAlarm(){}
        public void arm(long off,String token)throws Exception{if(failArm)throw new Exception("Alarm failure");arms++;}
        public void cancelAlarm(){}
        public void shutdown(){check(state.off==0&&!state.uncertain&&lastProgram==state.on);shutdowns++;}
        void pair()throws Exception{engine.save(time+3600,time+600,false);}
        void due(){time=state.off;}
    }
    public static void main(String[] args)throws Exception{
        Fake f=new Fake();f.engine.save(f.time+600,0,false);
        check(f.programs==1&&f.shutdowns==0&&f.state.on==f.time+600&&f.state.off==0);
        f=new Fake();f.engine.save(f.time+600,0,true);check(f.shutdowns==1&&f.arms==0);
        f=new Fake();f.failProgram=true;final Fake driver=f;
        rejects(()->driver.engine.save(driver.time+600,0,true));check(f.shutdowns==0&&f.state.uncertain);
        f=new Fake();f.failStore=1;final Fake disk=f;
        rejects(()->disk.engine.save(disk.time+600,0,true));check(f.programs==0&&f.shutdowns==0);
        f=new Fake();f.failStore=2;final Fake commit=f;
        rejects(()->commit.engine.save(commit.time+600,0,true));check(f.programs==1&&f.shutdowns==0&&f.state.uncertain);
        f=new Fake();f.denyShutdown=true;final Fake denied=f;
        rejects(()->denied.engine.save(denied.time+600,0,true));check(f.programs==0&&f.stores==0);
        f=new Fake();f.failArm=true;final Fake alarms=f;
        rejects(()->alarms.pair());check(f.state.off==0&&f.state.on>0&&f.shutdowns==0&&!f.state.error.isEmpty());
        f=new Fake();f.pair();String token=f.state.token;f.due();f.engine.fire(token);
        check(f.shutdowns==1&&f.state.off==0);f.engine.fire(token);check(f.shutdowns==1);
        f=new Fake();f.pair();String obsolete=f.state.token;f.pair();String current=f.state.token;
        f.due();f.engine.fire(obsolete);check(f.shutdowns==0&&f.state.token.equals(current));
        f.engine.fire(current);check(f.shutdowns==1);
        f=new Fake();f.pair();f.time=f.state.off-1;f.engine.fire(f.state.token);check(f.shutdowns==0&&f.state.off==0);
        f=new Fake();f.pair();f.time=f.state.off+61;f.engine.fire(f.state.token);check(f.shutdowns==0&&f.state.off==0);
        f=new Fake();f.pair();f.due();f.offset+=20;f.engine.fire(f.state.token);check(f.shutdowns==0);
        f=new Fake();f.pair();f.due();f.failProgram=true;final Fake fireFailure=f;
        rejects(()->fireFailure.engine.fire(fireFailure.state.token));check(f.shutdowns==0&&f.state.off==0);
        f=new Fake();f.pair();f.due();f.failStore=f.stores+1;final Fake consume=f;
        rejects(()->consume.engine.fire(consume.state.token));check(f.shutdowns==0);
        f=new Fake();f.pair();f.time=f.state.off+30;long on=f.state.on;f.engine.restore();
        check(f.shutdowns==0&&f.state.off==0&&f.state.on==on);
        f=new Fake();f.pair();f.engine.restore();check(f.shutdowns==0&&f.state.off>0&&f.arms==2);
        f=new Fake();f.pair();on=f.state.on;int writes=f.programs;f.engine.cancel(false);
        check(f.state.on==on&&f.state.off==0&&f.programs==writes&&f.shutdowns==0);
        f.engine.cancel(true);check(f.lastProgram==0&&f.state.on==0&&f.shutdowns==0);
        f=new Fake();f.pair();final Fake invalid=f;int before=f.programs;
        rejects(()->invalid.engine.save(invalid.time+600,invalid.time+540,false));check(f.programs==before&&f.state.off>0);
        final long now=f.time;
        rejects(()->ScheduleRules.validate(now+600,now-60,now));
        rejects(()->ScheduleRules.validate(now+600,now+60,now));
        rejects(()->ScheduleRules.validate(now+600,now+600,now));
        rejects(()->ScheduleRules.validate(now+600,now+660,now));
        rejects(()->ScheduleRules.validate(now+ScheduleRules.WEEK+60,0,now));
        rejects(()->ScheduleRules.validate(Long.MIN_VALUE,0,now));
        ScheduleRules.validate(now+600,now+120,now);checks++;
        System.out.println("PASS: "+checks+" schedule checks; all shutdown/hardware actions used fakes");
    }
}
