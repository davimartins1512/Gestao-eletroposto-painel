(function(root){
  let o,busy=false;
  const get=id=>document.getElementById(id);
  async function api(path,method='GET',body){
    const token=await o.token();if(!token)throw new Error('Sua sessão expirou.');
    const response=await fetch(o.backend+'/api/admin/rentals/'+path,{method,headers:{authorization:'Bearer '+token,'content-type':'application/json'},...(body?{body:JSON.stringify(body)}:{})});
    const data=await response.json();if(!response.ok||!data.success)throw new Error(data.error||'Falha ao consultar diárias.');return data;
  }
  const status={pending:'Pendente',paid:'Pago · baixa automática',settled_manual:'Quitado por fora',needs_review:'Conferir recebimento'};
  const delivery={waiting:'Aguardando envio/modelo',sending:'Envio reservado · confira se persistir',accepted:'Aceito pela Meta',delivered:'Entregue',read:'Lido',failed:'Falhou',unknown:'Envio incerto · conferir',blocked:'Bloqueado'};
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
  root.AndreaoRentals={init(options){o=options;},open,configure,saveSettings,saveDriver,retry};
})(globalThis);
