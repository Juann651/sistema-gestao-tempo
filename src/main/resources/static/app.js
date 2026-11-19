(function () {
  'use strict';

  // ---------- relógio sincronizado com o servidor ----------
  var offset = (Number(document.body.getAttribute('data-agora')) || Date.now()) - Date.now();
  function agora() { return Date.now() + offset; }
  function relogio(seg) {
    var neg = seg < 0, n = Math.abs(Math.round(seg));
    var h = Math.floor(n / 3600), m = Math.floor(n % 3600 / 60), s = n % 60;
    function p(x) { return (x < 10 ? '0' : '') + x; }
    return (neg ? '-' : '') + p(h) + ':' + p(m) + ':' + p(s);
  }

  // ---------- contadores de tarefa ----------
  // parado = tempo previsto · rodando = quanto falta (fica vermelho e negativo ao estourar) · feito = esforço gasto
  var foco = document.querySelector('.foco[data-fim]');
  var focoFim = foco ? Number(foco.getAttribute('data-fim')) : 0;
  var recarregou = false;
  function tick() {
    var cs = document.querySelectorAll('.cont');
    for (var i = 0; i < cs.length; i++) {
      var el = cs[i], estado = el.getAttribute('data-estado');
      if (estado === 'rodando') {
        var resta = (Number(el.getAttribute('data-fim')) - agora()) / 1000;
        el.textContent = relogio(Math.ceil(resta));
        el.classList.toggle('estourou', resta < 0);
        el.title = resta < 0 ? 'Passou do tempo reservado' : 'Tempo restante';
      } else {
        el.textContent = relogio(Number(el.getAttribute('data-seg')) || 0);
        el.title = estado === 'feito' ? 'Esforço gasto' : 'Tempo previsto';
      }
    }
    // quando a reserva de tempo acaba, o servidor libera as ações: recarrega uma vez
    if (focoFim > 0 && !recarregou && agora() >= focoFim) { recarregou = true; setTimeout(function () { location.reload(); }, 600); }
  }
  tick();
  setInterval(tick, 1000);

  // ---------- cartão de tarefa: clique abre/fecha os detalhes ----------
  function alternar(cab) {
    var card = cab.parentNode;
    var aberto = card.classList.toggle('aberto');
    cab.setAttribute('aria-expanded', aberto ? 'true' : 'false');
  }
  document.addEventListener('click', function (e) {
    var cab = e.target.closest ? e.target.closest('[data-toggle]') : null;
    if (!cab || e.target.closest('button, a, form, input, select, label')) return;
    alternar(cab);
  });
  document.addEventListener('keydown', function (e) {
    if ((e.key === 'Enter' || e.key === ' ') && e.target.hasAttribute && e.target.hasAttribute('data-toggle')) { e.preventDefault(); alternar(e.target); }
  });

  // ---------- grupo ao vivo: atualiza as regiões da página sozinho, sem recarregar ----------
  var vivoUrl = document.body.getAttribute('data-vivo');
  if (vivoUrl && window.fetch) {
    var buscando = false;
    var atualizarVivo = function () {
      if (document.hidden || buscando) return;
      buscando = true;
      fetch(vivoUrl, { credentials: 'same-origin', cache: 'no-store' })
        .then(function (r) {
          if (r.redirected && r.url.indexOf('/login') >= 0) { location.href = '/login'; return null; }
          return r.ok ? r.text() : null;
        })
        .then(function (html) {
          if (!html || html.indexOf('data-regiao') < 0) return;
          var caixa = document.createElement('div');
          caixa.innerHTML = html;
          caixa.querySelectorAll('[data-regiao]').forEach(function (novo) {
            var nome = novo.getAttribute('data-regiao');
            var atual = document.querySelector('[data-regiao="' + nome + '"]');
            if (!atual || atual.getAttribute('data-sig') === novo.getAttribute('data-sig')) return;   // nada mudou
            var abertos = [];
            atual.querySelectorAll('.tcard.aberto').forEach(function (c) { abertos.push(c.getAttribute('data-gtid')); });
            atual.replaceWith(novo);
            abertos.forEach(function (g) {
              var c = novo.querySelector('.tcard[data-gtid="' + g + '"]');
              if (c) { c.classList.add('aberto'); var h = c.querySelector('[data-toggle]'); if (h) h.setAttribute('aria-expanded', 'true'); }
            });
          });
          tick();
        })
        .catch(function () { /* sem internet por um instante: tenta de novo no próximo ciclo */ })
        .then(function () { buscando = false; });
    };
    setInterval(atualizarVivo, 4000);
    document.addEventListener('visibilitychange', function () { if (!document.hidden) atualizarVivo(); });
  }

  // ---------- pop-up de tempo gasto ----------
  var dlg = document.getElementById('dlgGasto');
  var frm = document.getElementById('frmGasto');
  if (dlg && frm) {
    document.addEventListener('click', function (e) {
      var b = e.target.closest ? e.target.closest('[data-concluir]') : null;
      if (b && !b.disabled) {
        var seg = Math.max(0, Math.round(Number(b.getAttribute('data-seg')) || 0));
        frm.action = '/tarefas/' + encodeURIComponent(b.getAttribute('data-id')) + '/concluir';
        frm.elements.volta.value = location.pathname;
        frm.elements.horas.value = Math.floor(seg / 3600);
        frm.elements.minutos.value = Math.floor(seg % 3600 / 60);
        frm.elements.segundos.value = seg % 60;
        document.getElementById('gastoNome').textContent = b.getAttribute('data-nome');
        document.getElementById('gastoErro').hidden = true;
        if (dlg.showModal) dlg.showModal(); else dlg.setAttribute('open', '');
        frm.elements.minutos.focus();
      }
      if (e.target.closest && e.target.closest('[data-fechar]')) dlg.close();
    });
    frm.addEventListener('submit', function (e) {
      var t = (+frm.elements.horas.value || 0) * 3600 + (+frm.elements.minutos.value || 0) * 60 + (+frm.elements.segundos.value || 0);
      if (t < 1) { e.preventDefault(); document.getElementById('gastoErro').hidden = false; }
    });
  }
})();
