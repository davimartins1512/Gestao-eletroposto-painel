(function(root){
  let o,busy=false,snapshot=null,loading=null,detailVersion=0;
  const get=id=>document.getElementById(id);
  async function api(path,method='GET',body){
    const token=await o.token();if(!token)throw new Error('Sua sessão expirou.');
    const response=await fetch(o.backend+'/api/admin/rentals/'+path,{method,headers:{authorization:'Bearer '+token,'content-type':'application/json'},...(body?{body:JSON.stringify(body)}:{})});
    const data=await response.json();if(!response.ok||!data.success)throw new Error(data.error||'Falha ao consultar diárias.');return data;
  }
  const status={pending:'Pendente',paid:'Pago · baixa automática',settled_manual:'Quitado por fora',needs_review:'Conferir recebimento'};
  const delivery={waiting:'Aguardando envio/modelo',sending:'Envio reservado · confira se persistir',accepted:'Aceito pela Meta',sent:'Enviado pela Meta',delivered:'Entregue',read:'Lido',failed:'Falhou',unknown:'Envio incerto · conferir',blocked:'Bloqueado'};
  const toCents=value=>Math.round((Number(value)||0)*100);
  const isoDay=n=>new Date(n).toISOString().slice(0,10);
  const shiftDay=(day,days)=>isoDay(Date.parse(day+'T00:00:00Z')+days*86400000);
  const br=day=>day?.split('-').reverse().join('/')||'—';
  const time=value=>value?new Date(value).toLocaleString('pt-BR',{timeZone:'America/Sao_Paulo',dateStyle:'short',timeStyle:'short'}):'—';
  function buildLedger(drivers,payments,invoices,today){
    const paid=new Map(), billed=new Map(), entries=new Map();
    for(const p of payments){
      const k=p.driver_id+'|'+p.payment_date;
      paid.set(k,(paid.get(k)||0)+toCents(p.amount));
    }
    for(const i of invoices)billed.set(i.driver_id+'|'+i.billing_date,i);
    for(const d of drivers){
      const rows=[];
      const start=Date.parse(d.start_date+'T00:00:00Z');
      const end=Date.parse((d.end_date&&d.end_date<today?d.end_date:today)+'T00:00:00Z');
      for(let t=end;Number.isFinite(start)&&Number.isFinite(t)&&t>=start;t-=86400000){
        const day=isoDay(t),key=d.id+'|'+day,i=billed.get(key);
        const due=toCents(i?.daily_value??d.daily_value),received=paid.get(key)||0,balance=Math.max(0,due-received);
        const review=i?.status==='needs_review'&&(balance>0||Boolean(i.transaction_nsu));
        rows.push({day,due,received,balance,review,late:day<today&&balance>0,invoice:i||null});
      }
      entries.set(d.id,rows);
    }
    return entries;
  }
  async function loadSnapshot(force=false){
    if(force)snapshot=null;
    if(snapshot)return snapshot;
    if(!loading)loading=Promise.resolve().then(()=>o.invoices()).then(rows=>snapshot=rows).finally(()=>{loading=null;});
    return loading;
  }
  function ledger(rows){return buildLedger(o.drivers(),o.payments(),rows,o.today());}
  function renderAlert(){
    const box=get('rentalArrears');if(!box)return;
    if(!snapshot){
      if(!loading)box.textContent='Conferindo diárias anteriores…';
      loadSnapshot().then(()=>{if(get('rentalArrears')===box)renderAlert();})
        .catch(()=>{box.textContent='Não foi possível conferir as diárias anteriores. Use Atualizar para tentar de novo.';});
      return;
    }
    let dates=0,total=0,people=0;
    for(const rows of ledger(snapshot).values()){
      const late=rows.filter(r=>r.late);
      if(late.length){people++;dates+=late.length;total+=late.reduce((sum,r)=>sum+r.balance,0);}
    }
    box.classList.toggle('rental-arrears-open',dates>0);
    box.innerHTML=dates?`<div><b>${dates} ${dates===1?'diária anterior em aberto':'diárias anteriores em aberto'}</b><br>
      <span>${people} ${people===1?'motorista':'motoristas'} · ${o.money(total/100)}</span></div>
      <button class="btn secondary small" onclick="AndreaoRentals.arrears()">Ver atrasos</button>`
      :'Nenhuma diária anterior em aberto.';
  }
  async function arrears(){
    try{
      const entries=ledger(await loadSnapshot()),esc=o.esc;
      const rows=o.drivers().map(d=>{
        const late=(entries.get(d.id)||[]).filter(r=>r.late);
        if(!late.length)return '';
        return `<tr><td><b>${esc(d.name)}</b><br>${esc(d.plate)}</td><td>${late.length}</td>
          <td>${br(late.at(-1).day)}</td><td>${o.money(late.reduce((s,r)=>s+r.balance,0)/100)}</td>
          <td><button class="btn secondary small" onclick="AndreaoRentals.details('${esc(d.id)}','${late.at(-1).day.slice(0,7)}','late')">Ver datas</button></td></tr>`;
      }).join('');
      o.modal('Diárias anteriores em aberto',rows?`<div class="table-wrap"><table><tr><th>Motorista</th><th>Dias</th><th>Mais antiga</th><th>Em aberto</th><th></th></tr>${rows}</table></div>`:'<p>Nenhuma diária anterior em aberto.</p>');
    }catch(e){alert(e.message);}
  }
  function messageHtml(attempt,esc,label){
    const r=attempt.receipt,flag=attempt.delivery_status||attempt.state||attempt.delivery_state;
    const err=flag==='failed'?(r?.error_message||attempt.error_message):attempt.error_message;
    const code=flag==='failed'?(r?.error_code||attempt.error_code):attempt.error_code;
    const moment=r?.read_at||r?.delivered_at||r?.failed_at||r?.sent_at||attempt.finished_at||attempt.started_at;
    return `<li><b>${label}</b> · ${esc(delivery[flag]||flag)}${moment?' · '+esc(time(moment)):''}
      ${code||err?`<br><small>${esc([code,err].filter(Boolean).join(' · '))}</small>`:''}</li>`;
  }
  async function details(id,month=o.today().slice(0,7),filter='all'){
    const d=o.drivers().find(d=>d.id===id);if(!d||!/^\d{4}-(0[1-9]|1[0-2])$/.test(month))return;
    const version=++detailVersion,esc=o.esc,first=month+'-01';
    const next=new Date(first+'T00:00:00Z');next.setUTCMonth(next.getUTCMonth()+1);
    const last=shiftDay(isoDay(next.valueOf()),-1);
    o.modal('Diárias · '+esc(d.name),`<p>${esc(d.car||'')} · ${esc(d.plate)}</p>
      <div class="filters"><label>Mês <input id="rentalDetailMonth" type="month" value="${month}" max="${o.today().slice(0,7)}" onchange="AndreaoRentals.changeDetail('${esc(id)}')"></label>
      <label>Mostrar <select id="rentalDetailFilter" onchange="AndreaoRentals.changeDetail('${esc(id)}')"><option value="all">Todas</option><option value="late" ${filter==='late'?'selected':''}>Atrasadas</option></select></label></div>
      <div id="rentalDetailBody" aria-live="polite">Carregando detalhes…</div>`);
    const target=get('rentalDetailBody');
    try{
      const invoices=await loadSnapshot();
      if(version!==detailVersion||get('rentalDetailBody')!==target)return;
      const rows=(ledger(invoices).get(id)||[]).filter(r=>r.day>=first&&r.day<=last&&(filter!=='late'||r.late));
      const total=(key)=>o.money(rows.reduce((sum,r)=>sum+r[key],0)/100);
      target.innerHTML=`<p><b>${total('due')}</b> devido · <b>${total('received')}</b> recebido · <b>${total('balance')}</b> em aberto</p>
        <p id="rentalMessageNote" class="tiny">Consultando histórico de envios…</p>
        ${rows.length?`<div class="table-wrap"><table><tr><th>Data da diária</th><th>Devido</th><th>Recebido</th><th>Falta</th><th>Situação</th><th>WhatsApp</th></tr>
        ${rows.map(r=>`<tr><td><b>${br(r.day)}</b></td><td>${o.money(r.due/100)}</td><td>${o.money(r.received/100)}</td>
          <td>${o.money(r.balance/100)}</td><td>${r.review?'Em conferência':!r.balance?'Quitada':r.late?(r.received?'Atrasada · parcial':'Atrasada'):(r.received?'Parcial':'Em aberto')}</td>
          <td data-rental-day="${r.day}"><span class="tiny">${r.invoice?esc(delivery[r.invoice.delivery_state]||r.invoice.delivery_state):'Sem cobrança automática'}</span></td></tr>`).join('')}</table></div>`:'<p>Nenhuma diária neste filtro.</p>'}
        <p class="tiny">Dias sem cobrança automática usam o valor atual do cadastro. Pagamentos são somados pela data da diária a que foram vinculados.</p>`;
      const note=get('rentalMessageNote');
      try{
        const logs=await api('drivers/'+encodeURIComponent(id)+'/messages?'+new URLSearchParams({from:first,to:last}));
        if(version!==detailVersion||get('rentalDetailBody')!==target)return;
        const byDate=new Map(logs.invoices.map(i=>[i.billing_date,i])),byInvoice=new Map();
        for(const a of logs.attempts){if(!byInvoice.has(a.invoice_id))byInvoice.set(a.invoice_id,[]);byInvoice.get(a.invoice_id).push(a);}
        for(const cell of target.querySelectorAll('[data-rental-day]')){
          const invoice=byDate.get(cell.dataset.rentalDay);if(!invoice)continue;
          const a=byInvoice.get(invoice.id)||[];
          const initial=a.some(x=>x.kind==='charge'&&(invoice.message_id?x.message_id===invoice.message_id:x.state===invoice.delivery_state));
          cell.innerHTML='<ul class="rental-message-log">'+(initial?'':messageHtml(invoice,esc,'Cobrança principal'))+
            a.map(x=>messageHtml(x,esc,x.kind==='reminder'?'Lembrete das 22h':'Cobrança principal')).join('')+'</ul>';
        }
        note.textContent=(logs.history_complete===false?'Por enquanto, o histórico mostra o último envio de cada diária. ':'')+'Aceito pela Meta significa que a solicitação foi recebida. Entregue e Lido dependem do retorno do WhatsApp.';
      }catch{if(get('rentalDetailBody')===target)note.textContent='O histórico detalhado do WhatsApp ainda não está disponível. Veja o último estado da cobrança quando houver.';}
    }catch(e){if(get('rentalDetailBody')===target)target.textContent=e.message;}
  }
  function changeDetail(id){void details(id,get('rentalDetailMonth').value,get('rentalDetailFilter').value);}
  async function saveReminder(){
    if(busy)return;busy=true;
    try{
      await api('reminder-settings','PUT',{reminder_template_name:get('rentalReminderTemplate').value.trim(),
        reminder_enabled:get('rentalReminderEnabled').checked});
      await open();
    }catch(e){const error=get('rentalBillingError');if(error)error.textContent=e.message;}finally{busy=false;}
  }
  async function open(){
    try{
      const data=await api('billing'),esc=o.esc;
      o.modal('Diárias automáticas dos carros',`<p>A cobrança é gerada uma vez por motorista e data, no horário de Brasília. O pagamento confirmado na InfinitePay registra a baixa em <b>Diárias</b> e atualiza os relatórios.</p>
        ${!data.whatsapp_enabled?'<p class="msg warning">O envio automático de WhatsApp está desativado na configuração do servidor.</p>':''}
        <h3>Modelo do WhatsApp</h3><p>Crie um modelo de utilidade na Meta, em português (Brasil), com o texto abaixo e cinco parâmetros, nesta ordem: nome, placa, data, valor e link.</p>
        <p><a href="https://business.facebook.com/wa/manage/message-templates/" target="_blank" rel="noopener noreferrer">Abrir modelos na Meta</a></p>
        <textarea readonly rows="4" aria-label="Texto do modelo da diária">${esc(data.template_body)}</textarea>
        <div class="form-grid"><div class="full"><label>Nome exato do modelo</label><input id="rentalTemplate" value="${esc(data.settings.template_name)}"></div></div>
        <label class="bulk-confirm"><input type="checkbox" id="rentalTemplateReady" ${data.settings.template_ready?'checked':''}> Este modelo está aprovado na Meta para minha conta.</label>
        <button class="btn secondary" type="button" onclick="AndreaoRentals.saveSettings()">Salvar configuração do modelo</button>
        ${data.reminder_template_body?`<h3>Lembrete das 22h · horário de Brasília</h3>
          <p>Uma mensagem extra no próprio dia, quando a cobrança principal foi aceita e a diária segue em aberto. Reutiliza o mesmo link. Diárias anteriores aparecem no alerta do painel.</p>
          <textarea readonly rows="4" aria-label="Texto do modelo de lembrete">${esc(data.reminder_template_body)}</textarea>
          <label for="rentalReminderTemplate">Nome do modelo de lembrete</label><input id="rentalReminderTemplate" value="${esc(data.settings.reminder_template_name||data.reminder_template_name)}">
          ${data.reminder_config_available===false?'<p class="msg warning">O lembrete ainda precisa ser liberado no banco de dados.</p>':`<label class="bulk-confirm"><input type="checkbox" id="rentalReminderEnabled" ${data.settings.reminder_enabled?'checked':''}> Programar lembrete das 22h. O envio começa quando a Meta liberar o modelo.</label>
          <button class="btn secondary" type="button" onclick="AndreaoRentals.saveReminder()">Salvar lembrete</button>`}`:''}
        <h3>Motoristas e horários</h3><p>Cadastre os carros em <b>Motoristas</b> e ative a cobrança de cada locação. Pausar o motorista interrompe os próximos envios.</p>
        <div class="table-wrap"><table><tr><th>Motorista</th><th>Placa</th><th>Diária</th><th>Automação</th><th></th></tr>${o.drivers().map(d=>`<tr><td>${esc(d.name)}</td><td>${esc(d.plate)}</td><td>${o.money(d.daily_value)}</td><td>${d.auto_billing_enabled?'Ativa · '+String(d.billing_hour).padStart(2,'0')+':00':'Desativada'}</td><td><button class="btn secondary small" onclick="AndreaoRentals.configure('${esc(d.id)}')">Configurar</button></td></tr>`).join('')||'<tr><td colspan="5">Cadastre seu primeiro motorista para começar.</td></tr>'}</table></div>
        <h3>Cobranças geradas</h3><p class="tiny">Últimas 1.000 cobranças. “Aceito pela Meta” não confirma a entrega. Respostas incertas exigem conferência para evitar duplicação.</p>
        <div class="table-wrap"><table><tr><th>Data da diária</th><th>Motorista / carro</th><th>Valor do link</th><th>Pagamento</th><th>WhatsApp</th><th>Ações</th></tr>${data.invoices.map(i=>`<tr><td>${esc(i.billing_date.split('-').reverse().join('/'))}</td><td>${esc(i.drivers?.name||'Motorista')}<br>${esc(i.drivers?.plate||'')}</td><td>${o.money(i.amount_cents/100)}</td><td>${status[i.status]||esc(i.status)}</td><td>${delivery[i.delivery_status||i.delivery_state]||esc(i.delivery_state)}${i.error_message?'<br><small>'+esc(i.error_message)+'</small>':''}</td><td>${i.checkout_url?`<a class="btn secondary small" href="${esc(i.checkout_url)}" target="_blank" rel="noopener noreferrer">Ver link</a>`:''} ${i.status==='pending'&&['failed','blocked'].includes(i.delivery_status||i.delivery_state)&&!['unknown','creating'].includes(i.checkout_state)?`<button class="btn secondary small" onclick="AndreaoRentals.retry('${esc(i.id)}')">Tentar novamente</button>`:''}</td></tr>`).join('')||'<tr><td colspan="6">As diárias aparecerão aqui no horário configurado.</td></tr>'}</table></div>
        <p id="rentalBillingError" role="alert"></p><button class="btn secondary" onclick="AndreaoRentals.open()">Atualizar cobranças</button>`);
    }catch(error){alert(error.message);}
  }
  function configure(id){
    const d=o.drivers().find(d=>d.id===id);if(!d)return;
    o.modal('Cobrança diária · '+o.esc(d.name),`<p>${o.esc(d.plate)} · diária de ${o.money(d.daily_value)}<br>WhatsApp: ${o.esc(d.phone||'Preencha o WhatsApp no cadastro do motorista.')}</p>
      <label class="bulk-confirm"><input id="rentalAutoEnabled" type="checkbox" ${d.auto_billing_enabled?'checked':''}> Enviar a cobrança diária automaticamente.</label>
      <div class="form-grid"><div><label>Hora do envio (Brasília)</label><select id="rentalAutoHour">${Array.from({length:24},(_,h)=>`<option value="${h}" ${h===(d.billing_hour??9)?'selected':''}>${String(h).padStart(2,'0')}:00</option>`).join('')}</select></div>
      <div><label>Início da automação</label><input id="rentalAutoFrom" type="date" value="${o.esc(d.billing_from||o.today())}" ${d.auto_billing_enabled?'':`min="${o.today()}"`}></div></div>
      <p>As diárias anteriores ao início da automação continuam no controle de débitos, sem envio retroativo. Se houver pagamento parcial registrado para a data, o link cobrará somente o restante.</p>
      <p id="rentalBillingError" role="alert"></p><button class="btn" onclick="AndreaoRentals.saveDriver('${o.esc(id)}')">Salvar cobrança diária</button>`);
  }
  async function saveSettings(){
    if(busy)return;busy=true;
    try{await api('settings','PUT',{template_name:get('rentalTemplate').value.trim(),template_ready:get('rentalTemplateReady').checked});await open();}
    catch(e){get('rentalBillingError').textContent=e.message;}finally{busy=false;}
  }
  async function saveDriver(id){
    if(busy)return;busy=true;
    try{await api('drivers/'+encodeURIComponent(id)+'/automation','PUT',{enabled:get('rentalAutoEnabled').checked,hour:Number(get('rentalAutoHour').value),from:get('rentalAutoFrom').value});await o.refresh();await open();}
    catch(e){get('rentalBillingError').textContent=e.message;}finally{busy=false;}
  }
  async function retry(id){try{await api('invoices/'+encodeURIComponent(id)+'/retry','POST',{});await open();}catch(e){alert(e.message);}}
  root.AndreaoRentals={init(options){o=options;},open,configure,saveSettings,saveDriver,retry,saveReminder,details,changeDetail,renderAlert,arrears,invalidate(){snapshot=null;},buildLedger};
})(globalThis);
