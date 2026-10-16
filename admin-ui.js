(function(global){
  'use strict';
  const views={
    dashboard:{title:'Visão geral',group:'PAINEL DE GESTÃO',description:'Recebimentos, recargas e pendências em um só lugar.'},
    ocpp:{title:'Carregadores',group:'OPERAÇÃO',description:'Acompanhe as estações e gerencie as recargas em operação.'},
    clients:{title:'Clientes',group:'OPERAÇÃO',description:'Cadastros, veículos, carteiras e histórico de recargas.'},
    charges:{title:'Cargas manuais',group:'OPERAÇÃO',description:'Organize os lançamentos e os recebimentos das cargas manuais.'},
    messages:{title:'Mensagens',group:'OPERAÇÃO',description:'Consulte os envios do WhatsApp e as confirmações de entrega.'},
    receivable:{title:'A receber',group:'FINANCEIRO',description:'Veja as pendências e registre os pagamentos recebidos.'},
    expenses:{title:'Despesas',group:'FINANCEIRO',description:'Acompanhe os custos e mantenha as saídas organizadas.'},
    reports:{title:'Relatórios',group:'FINANCEIRO',description:'Consulte os resultados por período, setor e carregador.'},
    drivers:{title:'Motoristas',group:'LOCAÇÃO',description:'Gerencie motoristas, veículos e contratos de locação.'},
    daily:{title:'Diárias',group:'LOCAÇÃO',description:'Acompanhe os valores devidos e os recebimentos de cada dia.'},
    data:{title:'Dados e backup',group:'SISTEMA',description:'Exporte os dados e consulte as informações da sua conta.'}
  };
  const paths={
    bolt:'<path d="m13 2-9 12h7l-1 8 10-12h-7z"/>',
    dashboard:'<rect x="3" y="3" width="7" height="7" rx="1.5"/><rect x="14" y="3" width="7" height="7" rx="1.5"/><rect x="3" y="14" width="7" height="7" rx="1.5"/><rect x="14" y="14" width="7" height="7" rx="1.5"/>',
    plug:'<path d="M8 3v4m8-4v4M6 7h12v4a6 6 0 0 1-12 0V7zm6 10v4"/>',
    users:'<circle cx="9" cy="8" r="3"/><path d="M3 21v-2a6 6 0 0 1 12 0v2m2-16a3 3 0 0 1 0 6m1 4a5 5 0 0 1 3 4v2"/>',
    message:'<path d="M21 11a9 9 0 0 1-9 9 9.3 9.3 0 0 1-4-.9L3 21l1.6-4.8A9 9 0 1 1 21 11Z"/><path d="M8 8h8M8 12h5"/>',
    wallet:'<rect x="3" y="5" width="18" height="15" rx="2"/><path d="M3 8h18M16 12h5v4h-5zM6 5V3h12v2"/>',
    receipt:'<path d="M5 3h14v18l-3-2-4 2-4-2-3 2V3zM8 7h8m-8 4h8m-8 4h4"/>',
    chart:'<path d="M4 3v17h17M8 15v-4m5 4V7m5 8V4"/>',
    car:'<path d="m5 8 2-4h10l2 4M3 8h18v9H3V8zm2 9v3m14-3v3M6 12h2m8 0h2"/>',
    calendar:'<rect x="3" y="5" width="18" height="16" rx="2"/><path d="M16 3v4M8 3v4M3 11h18m-14 4h2m3 0h2m3 0h2"/>',
    settings:'<path d="m9 3-1 3-3 1-2 4 2 3v3l4 3 3-1 3 1 4-3v-3l2-3-2-4-3-1-1-3H9z"/><circle cx="12" cy="12" r="3"/>',
    arrow:'<path d="M5 12h14m-5-5 5 5-5 5"/>',
    chevron:'<path d="m9 5 7 7-7 7"/>',
    clock:'<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>',
    logout:'<path d="M9 4H4v16h5m5-12 4 4-4 4M8 12h10"/>',
    menu:'<path d="M4 6h16M4 12h16M4 18h16"/>',
    lock:'<rect x="5" y="10" width="14" height="11" rx="2"/><path d="M8 10V7a4 4 0 0 1 8 0v3M12 14v3"/>',
    income:'<path d="M5 4h14v16H5zM8 8h8m-8 4h4m-4 4h3m4-2 2 2 3-4"/>',
    trend:'<path d="m3 17 6-6 4 4 8-9m-6 0h6v6"/>',
    charge:'<path d="M3 8h4v9H3zM7 11h10m0-3h4v9h-4zM10 5h4m-2-2v4"/>'
  };
  const escape=value=>String(value??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#039;'}[c]));
  function icon(name,extra=''){return '<svg class="icon '+extra+'" aria-hidden="true" viewBox="0 0 24 24">'+(paths[name]||paths.bolt)+'</svg>';}
  function operationalPoints(points){return(points||[]).filter(cp=>cp.active!==false);}
  function stationState(cp,connectors){
    if(!cp.online)return{label:'Sem conexão',tone:'offline'};
    const own=(connectors||[]).filter(c=>c.charge_point_id===cp.id&&Number(c.connector_id)>0);
    const usable=own.filter(c=>!(cp.ocpp_identity==='CP001'&&Number(c.connector_id)===1));
    if(usable.some(c=>c.status==='Charging'))return{label:'Em recarga',tone:'active'};
    if(usable.some(c=>['Faulted','Unavailable'].includes(c.status)||c.error_code&&c.error_code!=='NoError'))return{label:'Atenção',tone:'warning'};
    if(usable.some(c=>c.status==='Available'))return{label:'Disponível',tone:''};
    return{label:'Conectado',tone:''};
  }
  function enhance(root){
    (root||document).querySelectorAll('.card').forEach(card=>{
      if(card.querySelector(':scope > .metric-icon'))return;
      const label=card.querySelector(':scope > .label');if(!label)return;
      const text=label.textContent.toLowerCase();let name='wallet';
      if(text.includes('despesa'))name='receipt';else if(text.includes('lucro'))name='trend';
      else if(text.includes('receber'))name='clock';else if(text.includes('diária'))name='car';
      else if(text.includes('ocpp')||text.includes('recarga'))name='bolt';else if(text.includes('carregador'))name='plug';
      if(text.includes('receita recebida hoje'))card.classList.add('metric-primary');
      if(text.includes('receber'))card.classList.add('metric-pending');
      card.insertAdjacentHTML('beforeend','<span class="metric-icon" aria-hidden="true">'+icon(name)+'</span>');
    });
    const sector=document.querySelector('#dashCards > .card:last-child');
    if(sector&&sector.style.gridColumn==='1 / -1'){
      sector.classList.add('sector-card');sector.firstElementChild?.classList.add('sector-title');
      const grid=sector.children[1];grid?.classList.add('sector-grid');
      grid?.querySelectorAll('[style*="overflow:hidden"],[style*="overflow: hidden"]').forEach(el=>el.classList.add('sector-track'));
    }
  }
  function renderOverview(db){
    const cards=document.getElementById('dashCards'),root=document.getElementById('operationList');
    const points=operationalPoints(db.chargePoints),charging=(db.ocppSessions||[]).filter(s=>s.session_status==='charging').length;
    if(cards){
      cards.querySelectorAll('[data-admin-stat]').forEach(el=>el.remove());
      const before=Array.from(cards.children).find(el=>el.style.gridColumn==='1 / -1')||null;
      for(const [label,value,note]of[['Carregadores conectados',points.filter(cp=>cp.online).length+' / '+points.length,'Estações cadastradas em operação'],['Recargas em andamento',charging,'Sessões em andamento no sistema']]){
        const card=document.createElement('div');card.className='card';card.dataset.adminStat='true';
        card.innerHTML='<div class="label">'+label+'</div><div class="num">'+value+'</div><div class="sub">'+note+'</div>';
        cards.insertBefore(card,before);
      }
    }
    if(root)root.innerHTML=points.length?points.slice().sort((a,b)=>Number(!!b.online)-Number(!!a.online)||String(a.name).localeCompare(String(b.name),'pt-BR')).slice(0,5).map(cp=>{
      const state=stationState(cp,db.connectors);
      return '<div class="station-row"><div class="station-icon '+state.tone+'">'+icon('plug')+'</div><div class="station-copy"><strong>'+escape(String(cp.name||'Carregador').replace(/^Carregador\s*/i,''))+'</strong><small>'+escape(cp.ocpp_identity||'')+'</small></div><span class="station-state '+state.tone+'">'+state.label+'</span></div>';
    }).join(''):'<div class="empty">Os carregadores aparecerão aqui após o cadastro.</div>';
  }
  function refresh(id,db){
    const view=views[id]||views.dashboard;
    const title=document.getElementById('sectionTitle'),desc=document.getElementById('sectionDescription'),group=document.getElementById('sectionEyebrow'),shortcuts=document.getElementById('dashboardShortcuts');
    if(title)title.textContent=view.title;if(desc)desc.textContent=view.description;if(group)group.textContent=view.group;
    if(shortcuts)shortcuts.classList.toggle('hidden',id!=='dashboard');
    document.title=view.title+' · Andreão Eletroposto';
    document.querySelectorAll('.nav-btn[data-tab]').forEach(btn=>btn.setAttribute('aria-current',btn.dataset.tab===id?'page':'false'));
    if(id==='dashboard')renderOverview(db);
    enhance(document);
  }
  const sidebar=document.getElementById('sidebar'),menu=document.getElementById('menuBtn'),overlay=document.getElementById('navOverlay');
  document.querySelectorAll('[data-admin-icon]').forEach(el=>el.innerHTML=icon(el.dataset.adminIcon));
  const syncMenu=()=>{const open=!!sidebar?.classList.contains('open');menu?.setAttribute('aria-expanded',String(open));document.body.classList.toggle('nav-open',open);};
  if(sidebar)new MutationObserver(syncMenu).observe(sidebar,{attributes:true,attributeFilter:['class']});
  overlay?.addEventListener('click',()=>{sidebar?.classList.remove('open');menu?.focus();});
  document.addEventListener('keydown',e=>{if(e.key==='Escape'&&sidebar?.classList.contains('open')){sidebar.classList.remove('open');menu?.focus();}});
  global.addEventListener('resize',()=>{if(global.innerWidth>780)sidebar?.classList.remove('open');});
  global.AndreaoAdmin={icon,operationalPoints,stationState,enhance,refresh,views};
})(window);
