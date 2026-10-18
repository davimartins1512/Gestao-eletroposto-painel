(function(global){
  'use strict';
  function create({getClient,getState,isBusy,apply,onError=()=>{},schedule=setTimeout,cancel=clearTimeout}){
    let cursor='1970-01-01T00:00:00.000Z',generation=0,timer=null,running=null,stopped=false,failures=0;
    function seed(rows){
      const latest=(rows||[]).reduce((value,row)=>Math.max(value,Date.parse(row.updated_at)||0),0);
      cursor=new Date(Math.max(0,latest-120000)).toISOString();generation++;
    }
    async function read(table,since){
      const rows=[];
      for(let start=0;;start+=500){
        let query=getClient().from(table).select('*');
        if(since)query=query.gte('updated_at',since);
        const {data,error}=await query.order('id',{ascending:true}).range(start,start+499);
        if(error)throw error;
        rows.push(...(data||[]));if(!data||data.length<500)return rows;
      }
    }
    function next(){if(!stopped)timer=schedule(()=>{timer=null;void refresh();},Math.min(120000,20000*2**failures));}
    function refresh(){
      if(stopped)return Promise.resolve();
      if(running)return running;
      if(timer!==null){cancel(timer);timer=null;}
      const state=getState();
      if(!state?.userId||!state.eligible||isBusy()){next();return Promise.resolve();}
      const version=generation,userId=state.userId;
      running=(async()=>{
        try{
          const [chargePoints,connectors,sessions]=await Promise.all([
            read('charge_points'),read('connectors'),read('ocpp_sessions',cursor)
          ]);
          if(stopped||version!==generation||getState()?.userId!==userId||isBusy())return;
          apply({chargePoints,connectors,sessions});
          const latest=sessions.reduce((value,row)=>Math.max(value,Date.parse(row.updated_at)||0),Date.parse(cursor)+120000);
          cursor=new Date(Math.max(0,latest-120000)).toISOString();failures=0;
        }catch(error){failures=Math.min(3,failures+1);onError(error);}
        finally{running=null;next();}
      })();
      return running;
    }
    function stop(){stopped=true;generation++;if(timer!==null)cancel(timer);timer=null;}
    next();return{seed,refresh,stop};
  }
  global.AndreaoLive={create};
})(window);
