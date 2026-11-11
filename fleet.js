/* ANDREAO Fleet: painel de gestao. Nenhuma escrita em comandos do veiculo ou camera. */
(() => {
  'use strict';
  const byId = id => document.getElementById(id);
  const escapeHtml = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const brl = value => Number(value || 0).toLocaleString('pt-BR', {style:'currency', currency:'BRL'});
  const shortDate = value => value ? String(value).slice(0, 10).split('-').reverse().join('/') : '—';
  const number = (value, digits = 0) => value == null ? '—' : Number(value).toLocaleString('pt-BR', {maximumFractionDigits:digits});
  const plate = value => String(value || '').toUpperCase().replace(/[^A-Z0-9]/g, '');
  const state = {user:null, organization:null, vehicles:[], entries:[], telemetry:[], drivers:[], selected:null, tab:'history', pending:false};
  const labels = {available:'Disponível', rented:'Em locação', maintenance:'Manutenção', inactive:'Inativo'};
  const types = {maintenance:'Manutenção', expense:'Despesa', charge:'Recarga', trip:'Trajeto', inspection:'Vistoria', incident:'Ocorrência', other:'Outro'};
  let db;

  function notify(message, isError = false) {
    const node = byId('status');
    node.textContent = message;
    node.classList.toggle('error', isError);
  }
  function activeVehicle() { return state.vehicles.find(v => v.id === state.selected) || null; }
  function latest(vehicleId) { return state.telemetry.find(t => t.vehicle_id === vehicleId) || null; }
  function lastReading(value, suffix = '') { return value == null ? '—' : number(value, 1) + suffix; }
  function pill(status) { return '<span class="pill '+(['maintenance','inactive'].includes(status)?'warn':status==='rented'?'neutral':'')+'">'+escapeHtml(labels[status] || status)+'</span>'; }
  function field(name, label, input, hint = '') {
    return '<div class="field"><label for="f_'+name+'">'+label+'</label>'+input+(hint?'<small>'+hint+'</small>':'')+'</div>';
  }
  function textInput(name, value = '', max = 100, required = false) {
    return '<input id="f_'+name+'" name="'+name+'" value="'+escapeHtml(value)+'" maxlength="'+max+'" '+(required?'required':'')+'>';
  }
  function numberInput(name, value, step = '1') {
    return '<input id="f_'+name+'" name="'+name+'" type="number" step="'+step+'" min="0" value="'+escapeHtml(value ?? '')+'">';
  }
  function selectInput(name, options, selected) {
    return '<select id="f_'+name+'" name="'+name+'">'+options.map(([v,label])=>'<option value="'+escapeHtml(v)+'" '+(v === selected?'selected':'')+'>'+escapeHtml(label)+'</option>').join('')+'</select>';
  }
  function openDialog(kind, record = null) {
    const vehicle = activeVehicle();
    if (kind === 'entry' && !vehicle) return;
    const form = byId('editorForm');
    form.dataset.kind = kind;
    form.dataset.recordId = record?.id || '';
    form.dataset.vehicleId = vehicle?.id || '';
    byId('editorError').textContent = '';
    byId('editorTitle').textContent = kind === 'vehicle' ? (record?'Editar veículo':'Adicionar veículo') : (record?'Editar registro':'Novo registro · '+vehicle.plate);
    if (kind === 'vehicle') {
      byId('editorFields').innerHTML =
        field('plate','Placa *',textInput('plate',record?.plate,7,true),'Somente letras e números, sem hífen.')+
        field('model','Modelo *',selectInput('model', [['BYD Dolphin Mini','BYD Dolphin Mini'],['BYD Yuan Plus','BYD Yuan Plus'],['BYD Dolphin Plus','BYD Dolphin Plus']],record?.model || 'BYD Dolphin Mini'))+
        field('nickname','Identificação',textInput('nickname',record?.nickname,100))+
        field('vehicle_year','Ano',numberInput('vehicle_year',record?.vehicle_year))+
        field('usage_type','Finalidade',selectInput('usage_type',[['rental','Locação'],['private','Particular']],record?.usage_type || 'rental'))+
        field('status','Situação',selectInput('status',Object.entries(labels),record?.status || 'available'))+
        field('vin','Chassi / VIN (opcional)',textInput('vin',record?.vin,40))+
        field('notes','Observações','<textarea id="f_notes" name="notes" maxlength="2000" rows="3">'+escapeHtml(record?.notes || '')+'</textarea>');
    } else {
      byId('editorFields').innerHTML =
        field('entry_type','Tipo de registro *',selectInput('entry_type',Object.entries(types),record?.entry_type || 'maintenance'))+
        field('occurred_on','Data *','<input id="f_occurred_on" name="occurred_on" type="date" required value="'+escapeHtml(record?.occurred_on || new Date().toLocaleDateString('en-CA'))+'">')+
        field('description','Descrição *',textInput('description',record?.description,300,true))+
        field('amount_brl','Custo em reais',numberInput('amount_brl',record?.amount_brl ?? 0,'0.01'))+
        field('energy_kwh','Energia carregada (kWh)',numberInput('energy_kwh',record?.energy_kwh,'0.001'))+
        field('odometer_km','Odômetro (km)',numberInput('odometer_km',record?.odometer_km,'0.1'))+
        field('notes','Detalhes','<textarea id="f_notes" name="notes" maxlength="2000" rows="3">'+escapeHtml(record?.notes || '')+'</textarea>');
    }
    byId('editor').showModal();
    byId('editorFields').querySelector('input,select')?.focus();
  }
  function closeDialog() { byId('editor').close(); }

  async function saveForm(event) {
    event.preventDefault();
    if (state.pending) return;
    const form = byId('editorForm'), values = Object.fromEntries(new FormData(form)), kind = form.dataset.kind;
    const isVehicle = kind === 'vehicle';
    const id = form.dataset.recordId;
    const payload = isVehicle ? {
      plate:plate(values.plate),model:String(values.model || '').trim(),
      nickname:values.nickname?.trim() || null,
      vehicle_year:values.vehicle_year?Number(values.vehicle_year):null,
      usage_type:values.usage_type,status:values.status,
      vin:values.vin?.trim() || null,notes:values.notes?.trim() || null
    } : {
      entry_type:values.entry_type,occurred_on:values.occurred_on,description:values.description?.trim(),
      amount_brl:Number(values.amount_brl || 0),energy_kwh:values.energy_kwh===''?null:Number(values.energy_kwh),
      odometer_km:values.odometer_km===''?null:Number(values.odometer_km),notes:values.notes?.trim() || null
    };
    if (isVehicle && (!/^[A-Z0-9]{7}$/.test(payload.plate) || !payload.model)) return byId('editorError').textContent = 'Informe uma placa válida e o modelo.';
    if (!isVehicle && (!payload.description || payload.description.length < 2 || !payload.occurred_on)) return byId('editorError').textContent = 'Informe data e descrição.';
    state.pending = true;byId('saveEditor').disabled = true;byId('editorError').textContent = '';
    try {
      const table = isVehicle ? 'fleet_vehicles' : 'fleet_entries';
      if (!id) Object.assign(payload,isVehicle ?
        {user_id:state.user.id,organization_id:state.organization.id} :
        {user_id:state.user.id,vehicle_id:form.dataset.vehicleId});
      else payload.updated_at = new Date().toISOString();
      const query = id ? db.from(table).update(payload).eq('id',id).eq('user_id',state.user.id) :
        db.from(table).insert(payload);
      const {data,error} = await query.select('id').single();
      if (error) throw error;
      if (isVehicle) state.selected = data.id;
      closeDialog();
      await load();
      notify(isVehicle ? 'Veículo salvo com sucesso.' : 'Registro salvo com sucesso.');
    } catch (error) {
      byId('editorError').textContent = error.code === '23505' ? 'Essa placa já está cadastrada nesta frota.' : (error.message || 'Falha ao salvar.');
    } finally {state.pending = false;byId('saveEditor').disabled = false;}
  }
  async function ensureOrganization() {
    const {data,error} = await db.from('fleet_organizations').select('*').eq('user_id',state.user.id).order('created_at',{ascending:true}).limit(1);
    if (error) throw error;
    if (data?.length) return data[0];
    const created = await db.from('fleet_organizations').insert({user_id:state.user.id,name:'ANDREAO ELETROPOSTO'}).select('*').single();
    if (created.error) throw created.error;
    return created.data;
  }
  async function load() {
    notify('Sincronizando frota...');
    state.organization = await ensureOrganization();
    const [vehicles,entries,telemetry,drivers] = await Promise.all([
      db.from('fleet_vehicles').select('*').eq('organization_id',state.organization.id).order('created_at',{ascending:true}),
      db.from('fleet_entries').select('*').eq('user_id',state.user.id).order('occurred_on',{ascending:false}).limit(500),
      db.from('fleet_telemetry').select('*').eq('user_id',state.user.id).order('recorded_at',{ascending:false}).limit(1000),
      db.from('drivers').select('plate,name,active').eq('user_id',state.user.id).limit(500)
    ]);
    for (const result of [vehicles,entries,telemetry]) if (result.error) throw result.error;
    state.vehicles = vehicles.data || [];
    state.entries = entries.data || [];
    state.telemetry = telemetry.data || [];
    state.drivers = drivers.error ? [] : drivers.data || [];
    if (!state.vehicles.some(v=>v.id===state.selected)) state.selected = state.vehicles[0]?.id || null;
    byId('content').hidden = false;
    render();
    notify('Frota sincronizada. Leituras ao vivo dependem da conexão com os veículos.');
  }
  function render() {
    const all = state.vehicles, costs = state.entries.filter(e=>['maintenance','expense','charge'].includes(e.entry_type));
    byId('statTotal').textContent = all.length;
    byId('statRented').textContent = all.filter(v=>v.status==='rented').length;
    byId('statMaintenance').textContent = all.filter(v=>v.status==='maintenance').length;
    byId('statExpenses').textContent = brl(costs.reduce((sum,e)=>sum+Number(e.amount_brl||0),0));
    const query = byId('search').value.toLowerCase().trim();
    const filtered = all.filter(v=>([v.plate,v.nickname,v.model].join(' ').toLowerCase()).includes(query));
    byId('vehicles').innerHTML = filtered.length ? filtered.map(v=>
      '<button type="button" class="vehicle '+(v.id===state.selected?'active':'')+'" data-vehicle="'+escapeHtml(v.id)+'"><span><strong>'+escapeHtml(v.nickname||v.model)+'</strong><small>'+escapeHtml(v.plate)+' · '+escapeHtml(v.model)+'</small></span>'+pill(v.status)+'</button>').join('') :
      '<div class="empty">Nenhum veículo encontrado. Use “Adicionar veículo”.</div>';
    renderDetail();
  }
  function renderDetail() {
    const v = activeVehicle(), root = byId('vehicleDetail');
    if (!v) {root.innerHTML='<div class="empty"><h2>Comece cadastrando um veículo</h2><p>Os registros e as leituras aparecerão aqui.</p></div>';return;}
    const t = latest(v.id), recent = t && Date.now()-Date.parse(t.recorded_at)<120000;
    const compatibleDriver = state.drivers.find(d=>plate(d.plate)===v.plate);
    const title = '<div class="detail-head"><div><h2>'+escapeHtml(v.nickname||v.model)+'</h2><p>'+escapeHtml(v.plate)+' · '+escapeHtml(v.model)+(v.vehicle_year?' · '+v.vehicle_year:'')+'</p>'+pill(v.status)+'</div><button type="button" class="ghost" data-action="edit-vehicle">Editar</button></div>';
    const metrics = '<div class="meters"><div class="meter"><small>Carga atual (SOC)</small><strong>'+lastReading(t?.soc_pct,'%')+'</strong></div><div class="meter"><small>Saúde (SOH)</small><strong>'+lastReading(t?.soh_pct,'%')+'</strong></div><div class="meter"><small>Odômetro</small><strong>'+lastReading(t?.odometer_km,' km')+'</strong></div></div>';
    const origin = t ? '<div class="notice'+(recent?'':' warn')+'">Última leitura: '+new Date(t.recorded_at).toLocaleString('pt-BR')+' · Origem: '+escapeHtml(t.source)+'. '+(recent?'Dados recentes; a conexão contínua ainda deve ser validada.':'Leitura antiga, não é monitoramento ao vivo.')+'</div>' :
      '<div class="notice warn">Veículo cadastrado, mas ainda não existe integração de telemetria verificada. SOC, SOH, GPS e câmera não são dados disponíveis neste momento.</div>';
    const driver = compatibleDriver && v.usage_type==='rental' ? '<p><strong>Motorista cadastrado com a mesma placa:</strong> '+escapeHtml(compatibleDriver.name)+' <small>(confira a associação no painel de Motoristas)</small></p>' : '';
    const tabnames = [['history','Histórico'],['battery','Bateria e localização'],['camera','Câmeras e segurança']];
    const tabs = '<div class="tabs">'+tabnames.map(([key,label])=>'<button type="button" class="ghost '+(state.tab===key?'selected':'')+'" data-tab="'+key+'">'+label+'</button>').join('')+'</div>';
    let body = '';
    if (state.tab === 'history') {
      const entries = state.entries.filter(e=>e.vehicle_id===v.id);
      body = '<div class="section-actions"><h3>Operação e despesas</h3><button type="button" class="primary" data-action="add-entry">+ Registro</button></div>'+
        (entries.length?entries.map(e=>'<div class="row"><div><strong>'+escapeHtml(types[e.entry_type]||e.entry_type)+' · '+escapeHtml(e.description)+'</strong><small>'+shortDate(e.occurred_on)+(e.odometer_km!=null?' · '+number(e.odometer_km,1)+' km':'')+(e.energy_kwh!=null?' · '+number(e.energy_kwh,2)+' kWh':'')+'</small>'+(e.notes?'<small>'+escapeHtml(e.notes)+'</small>':'')+'</div><div class="right"><strong>'+brl(e.amount_brl)+'</strong><button class="ghost" type="button" data-edit-entry="'+escapeHtml(e.id)+'">Editar</button></div></div>').join(''):'<div class="empty">Sem registros. Cadastre manutenção, recargas, despesas, trajetos ou vistorias.</div>');
    } else if (state.tab === 'battery') {
      body = '<div class="section-actions"><h3>Diagnóstico elétrico e GPS</h3></div>'+
        '<div class="row"><span>Autonomia</span><strong>'+lastReading(t?.range_km,' km')+'</strong></div>'+
        '<div class="row"><span>Temperatura da bateria</span><strong>'+lastReading(t?.battery_temp_c,' °C')+'</strong></div>'+
        '<div class="row"><span>Bateria auxiliar 12V</span><strong>'+lastReading(t?.voltage_12v,' V')+'</strong></div>'+
        '<div class="row"><span>Velocidade</span><strong>'+lastReading(t?.speed_kmh,' km/h')+'</strong></div>'+
        '<div class="row"><span>Carregando</span><strong>'+(t?.charging==null?'—':t.charging?'Sim':'Não')+'</strong></div>'+
        '<div class="row"><span>GPS</span><strong>'+(t?.latitude!=null&&t?.longitude!=null?'Coordenadas disponíveis (acesso restrito)':'Sem leitura')+'</strong></div>'+
        '<div class="notice">O SOH só será exibido quando fornecido por integração ou diagnóstico identificável. Não calculamos valores falsos com base na porcentagem de carga.</div>';
    } else {
      body = '<div class="section-actions"><h3>Câmeras originais e segurança</h3></div>'+
        '<div class="notice warn">Não há conexão autorizada com as câmeras deste veículo. A transmissão de estacionamento, gravações e modo sentinela exigem validação de compatibilidade e controles de privacidade. Nenhum vídeo é exibido ou gravado por este painel.</div>'+
        '<div class="row"><span>Transmissão ao vivo</span><strong>Não integrada</strong></div>'+
        '<div class="row"><span>Gravações e sentinela</span><strong>Não integradas</strong></div>'+
        '<div class="row"><span>Bloqueio / imobilização</span><strong>Não disponível</strong></div>';
    }
    root.innerHTML=title+metrics+origin+driver+tabs+body;
  }
  async function bootstrap() {
    const cfg=window.APP_CONFIG;
    if (!cfg?.supabaseUrl || !cfg?.supabaseKey || !window.supabase?.createClient) {
      notify('Configuração de acesso indisponível. Retorne ao painel administrativo.',true);return;
    }
    db=window.supabase.createClient(cfg.supabaseUrl,cfg.supabaseKey,{auth:{persistSession:true,autoRefreshToken:true}});
    const {data,error}=await db.auth.getUser();
    if (error || !data?.user) {
      notify('Acesso restrito. Entre primeiro no painel administrativo.',true);
      return;
    }
    state.user=data.user;
    try {await load();}
    catch (err) {
      const pending=['42P01','PGRST205','42501','PGRST204'].includes(err?.code);
      notify(pending?'Módulo preparado, mas o esquema do banco ou as permissões ainda não foram liberados. Nenhum dado foi alterado.':'Erro ao carregar a frota: '+String(err?.message||'Falha de conexão'),true);
    }
  }
  byId('refresh').addEventListener('click',()=>{void load().catch(err=>notify(err.message,true));});
  byId('newVehicle').addEventListener('click',()=>openDialog('vehicle'));
  byId('search').addEventListener('input',render);
  byId('closeEditor').addEventListener('click',closeDialog);
  byId('cancelEditor').addEventListener('click',closeDialog);
  byId('editorForm').addEventListener('submit',event=>{void saveForm(event);});
  document.addEventListener('click',event=>{
    const vehicleButton = event.target.closest('[data-vehicle]');
    if (vehicleButton) {state.selected=vehicleButton.dataset.vehicle;state.tab='history';render();return;}
    const tabButton = event.target.closest('[data-tab]');
    if (tabButton) {state.tab=tabButton.dataset.tab;renderDetail();return;}
    const action=event.target.closest('[data-action]')?.dataset.action;
    if (action==='edit-vehicle') openDialog('vehicle',activeVehicle());
    if (action==='add-entry') openDialog('entry');
    const edit=event.target.closest('[data-edit-entry]')?.dataset.editEntry;
    if (edit) {const entry=state.entries.find(e=>e.id===edit);if(entry)openDialog('entry',entry);}
  });
  void bootstrap();
})();
