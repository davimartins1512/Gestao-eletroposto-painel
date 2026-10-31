(function(root){
  'use strict';
  const ui=root.AndreaoUI;
  const value=n=>Number.isFinite(Number(n))?Math.max(0,Number(n)):0;
  const cents=n=>Math.round(value(n)*100);
  const empty=()=>({count:0,energy:0,billed:0,settled:0,pending:0,review:0,reviewCount:0,live:0,liveCount:0});
  function add(t,s){
    const amount=cents(s.total_amount);
    if(s.session_status==='charging'){t.live+=amount;t.liveCount++;return;}
    if(s.session_status!=='finished')return;
    if(s.reconciliation_required){t.review+=amount;t.reviewCount++;return;}
    const billable=s.payment_status==='not_required'?0:amount;
    t.count++;t.energy+=value(s.energy_kwh);t.billed+=billable;
    // Paid is a settlement state, including manual approvals and wallet debits.
    // Partial payments use the canonical field once, never add both paid fields.
    const paid=s.payment_status==='paid'?billable:Math.min(billable,cents(ui.sessionPaidAmount(s)));
    t.settled+=paid;
    if(!['paid','not_required'].includes(s.payment_status))t.pending+=amount-paid;
  }
  function report(sessions,points,filters={}){
    const names=new Map(points.map(p=>[String(p.id),p]));
    const rows=sessions.filter(s=>{
      const day=ui.operationalDay(s.started_at),cp=names.get(String(s.charge_point_id));
      const text=[cp?.name,cp?.ocpp_identity,s.plate,s.transaction_id].join(' ').toLocaleLowerCase('pt-BR');
      const outstanding=!['paid','not_required'].includes(s.payment_status)&&cents(s.total_amount)>cents(ui.sessionPaidAmount(s));
      return (!filters.point||String(s.charge_point_id)===filters.point)&&
        (!filters.connector||String(s.connector_id)===filters.connector)&&
        (!filters.from||(day&&day>=filters.from))&&(!filters.to||(day&&day<=filters.to))&&
        (!filters.query||text.includes(filters.query.toLocaleLowerCase('pt-BR')))&&
        (!filters.payment||(filters.payment==='pending'?outstanding:
          filters.payment==='review'?!!s.reconciliation_required:s.payment_status===filters.payment));
    }).sort((a,b)=>String(b.started_at||'').localeCompare(String(a.started_at||''))||String(a.id).localeCompare(String(b.id)));
    const totals=empty(),groups=new Map();
    for(const s of rows){
      const key=JSON.stringify([s.charge_point_id,s.connector_id]);
      if(!groups.has(key))groups.set(key,{point:String(s.charge_point_id||''),connector:s.connector_id,
        name:names.get(String(s.charge_point_id))?.name||'Carregador sem cadastro',...empty()});
      add(totals,s);add(groups.get(key),s);
    }
    return {rows,totals,groups:Array.from(groups.values()).sort((a,b)=>a.name.localeCompare(b.name,'pt-BR')||Number(a.connector)-Number(b.connector))};
  }
  let page=1,last=null;
  const get=id=>document.getElementById(id);
  function options(select,entries,placeholder){
    const previous=select.value;
    const signature=JSON.stringify(entries);
    if(select.dataset.options===signature)return;
    select.replaceChildren(new Option(placeholder,''),...entries.map(([id,name])=>new Option(name,id)));
    if(entries.some(([id])=>id===previous))select.value=previous;
    select.dataset.options=signature;
  }
  function render({sessions,points,connectors,money,esc}){
    const used=new Set(sessions.map(s=>String(s.charge_point_id)));
    options(get('ocppPointFilter'),points.filter(p=>p.active!==false||used.has(String(p.id)))
      .sort((a,b)=>String(a.name).localeCompare(String(b.name),'pt-BR')).map(p=>[String(p.id),p.name||p.ocpp_identity]),'Todos os carregadores');
    const point=get('ocppPointFilter').value;
    const ids=new Set([...connectors,...sessions].filter(s=>(!point||String(s.charge_point_id)===point)&&Number(s.connector_id)>0).map(s=>String(s.connector_id)));
    options(get('ocppConnectorFilter'),Array.from(ids).sort((a,b)=>Number(a)-Number(b)).map(id=>[id,'Bico '+id]),'Todos os bicos');
    const filters={point,connector:get('ocppConnectorFilter').value,from:get('ocppDateFrom').value,to:get('ocppDateTo').value,
      query:get('ocppSearch').value.trim(),payment:get('ocppPaymentStatus').value};
    const invalid=filters.from&&filters.to&&filters.from>filters.to;
    get('ocppHistoryError').textContent=invalid?'A data final deve ser igual ou posterior à data inicial.':'';
    last=report(invalid?[]:sessions,points,filters);
    const t=last.totals;
    get('ocppHistorySummary').innerHTML=[['Faturamento finalizado',money(t.billed/100),t.count+' cargas finalizadas'],
      ['Valor quitado',money(t.settled/100),'Inclui carteira e pagamentos manuais'],['A receber',money(t.pending/100),'Saldo das cargas finalizadas'],
      ['Energia finalizada',t.energy.toLocaleString('pt-BR',{maximumFractionDigits:2})+' kWh','Mesmo período e filtros']]
      .map(([label,number,note])=>'<div class="card"><div class="label">'+esc(label)+'</div><div class="num">'+esc(number)+'</div><div class="sub">'+esc(note)+'</div></div>').join('');
    get('ocppHistoryExceptions').textContent=t.liveCount+' em andamento ('+money(t.live/100)+') · '+t.reviewCount+' em conferência ('+money(t.review/100)+'). Valores separados do faturamento finalizado.';
    get('ocppRevenueByConnector').innerHTML=last.groups.length?'<table><thead><tr><th>Carregador</th><th>Bico</th><th>Finalizadas</th><th>Energia</th><th>Faturamento</th><th>Quitado</th><th>A receber</th><th>Em conferência</th><th>Em andamento</th></tr></thead><tbody>'+last.groups.map(g=>'<tr><td><b>'+esc(g.name)+'</b></td><td>'+esc(g.connector||'—')+'</td><td>'+g.count+'</td><td>'+g.energy.toLocaleString('pt-BR',{maximumFractionDigits:2})+' kWh</td><td>'+money(g.billed/100)+'</td><td>'+money(g.settled/100)+'</td><td>'+money(g.pending/100)+'</td><td>'+money(g.review/100)+' ('+g.reviewCount+')</td><td>'+money(g.live/100)+' ('+g.liveCount+')</td></tr>').join('')+'</tbody></table>':'<div class="empty">Nenhuma recarga corresponde aos filtros.</div>';
    const pages=Math.max(1,Math.ceil(last.rows.length/50));page=Math.min(page,pages);
    get('ocppHistoryPage').textContent=last.rows.length+' cargas · Página '+page+' de '+pages;
    get('ocppHistoryPrev').disabled=page<=1;get('ocppHistoryNext').disabled=page>=pages;
    return last.rows.slice((page-1)*50,page*50);
  }
  function bind(refresh){
    for(const id of ['ocppPointFilter','ocppConnectorFilter','ocppDateFrom','ocppDateTo','ocppPaymentStatus','ocppSearch'])
      get(id).addEventListener(id==='ocppSearch'?'input':'change',()=>{page=1;if(id==='ocppPointFilter')get('ocppConnectorFilter').value='';refresh();});
    get('ocppHistoryPrev').addEventListener('click',()=>{page=Math.max(1,page-1);refresh();});
    get('ocppHistoryNext').addEventListener('click',()=>{page++;refresh();});
    get('ocppHistoryPeriod').addEventListener('change',event=>{
      const today=ui.operationalDay(),period=event.target.value;
      get('ocppDateFrom').value=period==='today'?today:period==='month'?today.slice(0,7)+'-01':'';
      get('ocppDateTo').value=period?today:'';page=1;refresh();
    });
    for(const id of ['ocppDateFrom','ocppDateTo'])get(id).addEventListener('change',()=>{get('ocppHistoryPeriod').value='';});
    get('ocppHistoryClear').addEventListener('click',()=>{
      for(const id of ['ocppPointFilter','ocppConnectorFilter','ocppDateFrom','ocppDateTo','ocppPaymentStatus','ocppSearch','ocppHistoryPeriod'])get(id).value='';
      page=1;refresh();
    });
  }
  root.AndreaoHistory={report,render,bind};
})(globalThis);
