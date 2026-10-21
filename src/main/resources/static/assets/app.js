/* ============================================================
   校园二手交易平台 · 前端逻辑
   ------------------------------------------------------------
   对接的是本项目的真实后端接口,没有任何 mock 数据:
     GET  /banner/list                 轮播图
     GET  /category/list               分类
     GET  /product/list                商品列表(keyword / categoryId / 分页)
     GET  /product/detail/{id}         商品详情(浏览量 +1)
     POST /auth/login                  登录,返回 JWT
     POST /auth/register               注册
     GET  /user/info                   当前用户
     GET  /user/products               我发布的
     GET  /user/favorites              我收藏的
     POST /product/publish             发布商品
     POST /product/publishWithImages   发布商品 + 图片
     PUT  /product/status/{id}?status= 下架(2)/重新提交审核(0)/标记已售(3)
     DEL  /product/remove/{id}         删除商品(卖家或审核员)
     GET  /admin/products?status=      审核台列表(审核员)
     POST /admin/products/{id}/audit   审核通过/驳回(审核员)
     GET  /admin/stats                 待审核数量(审核员)
     POST /favorites/{id}              收藏
     DEL  /favorites/{id}              取消收藏
     GET  /favorites/{id}              是否已收藏
   后端统一响应:{ code, message, data, timestamp },code === 200 为成功。
   ============================================================ */

(function () {
  'use strict';

  var TOKEN_KEY = 'csh_token';
  var USER_KEY = 'csh_user';

  var CATEGORY_EMOJI = {
    1: '💻', 2: '📚', 3: '🏠', 4: '🏸', 5: '👕', 6: '📦'
  };

  var STATUS_TEXT = {
    0: { text: '待审核', cls: 'badge-status--pending' },
    1: { text: '在售', cls: '' },
    2: { text: '已下架', cls: 'badge-status--off' },
    3: { text: '已售出', cls: 'badge-status--sold' },
    4: { text: '审核驳回', cls: 'badge-status--rejected' }
  };

  var state = {
    view: 'home',
    page: 1,
    pageSize: 12,
    keyword: '',
    categoryId: null,
    categories: [],
    token: null,
    user: null,
    loading: false
  };

  var el = {
    authArea: document.getElementById('authArea'),
    banners: document.getElementById('banners'),
    filterBar: document.getElementById('filterBar'),
    searchInput: document.getElementById('searchInput'),
    categoryChips: document.getElementById('categoryChips'),
    listTitle: document.getElementById('listTitle'),
    listMeta: document.getElementById('listMeta'),
    listArea: document.getElementById('listArea'),
    pager: document.getElementById('pager'),
    modal: document.getElementById('modal'),
    modalBody: document.getElementById('modalBody'),
    toast: document.getElementById('toast')
  };

  /* ============================================================
     基础工具
     ============================================================ */

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }

  /**
   * 取表单字段值。
   * 不能直接用 form.title.value —— HTMLFormElement 自身就有 title 属性,
   * 命名访问在部分浏览器上会被内置属性挡住,拿到空字符串。
   */
  function fieldValue(form, name) {
    var node = form.elements.namedItem(name);
    return node && typeof node.value === 'string' ? node.value : '';
  }

  function money(v) {
    var n = Number(v);
    if (!isFinite(n)) return '—';
    return n % 1 === 0 ? String(n) : n.toFixed(2);
  }

  function fmtTime(s) {
    if (!s) return '—';
    var t = String(s).replace('T', ' ');
    return t.length >= 16 ? t.slice(0, 16) : t;
  }

  function categoryNameOf(id) {
    if (id == null) return '未分类';
    var c = state.categories.filter(function (x) { return Number(x.id) === Number(id); })[0];
    return c ? c.name : '未分类';
  }

  function categoryEmojiOf(id, name) {
    if (id != null && CATEGORY_EMOJI[Number(id)]) return CATEGORY_EMOJI[Number(id)];
    var c = state.categories.filter(function (x) { return x.name === name; })[0];
    if (c && CATEGORY_EMOJI[Number(c.id)]) return CATEGORY_EMOJI[Number(c.id)];
    return '📦';
  }

  function toast(msg, ms) {
    el.toast.textContent = msg;
    el.toast.classList.add('is-show');
    clearTimeout(toast._timer);
    toast._timer = setTimeout(function () {
      el.toast.classList.remove('is-show');
    }, ms || 2200);
  }

  /* ============================================================
     请求封装
     ============================================================ */

  function ApiError(message, code) {
    this.name = 'ApiError';
    this.message = message;
    this.code = code;
  }
  ApiError.prototype = Object.create(Error.prototype);

  function api(path, opts) {
    opts = opts || {};
    var method = opts.method || 'GET';
    var headers = {};
    var payload;

    if (opts.auth) {
      if (!state.token) {
        return Promise.reject(new ApiError('请先登录', 401));
      }
      headers.Authorization = 'Bearer ' + state.token;
    } else if (opts.optionalAuth && state.token) {
      // 可选登录:带了 token 后端就按身份返回(如卖家看自己的待审核商品),
      // 没带也能正常浏览
      headers.Authorization = 'Bearer ' + state.token;
    }

    if (opts.form) {
      // FormData 必须交给浏览器自己设置 Content-Type(要带 boundary),
      // 手动指定会导致后端解析不出任何 part。
      payload = opts.form;
    } else if (opts.body !== undefined && opts.body !== null) {
      headers['Content-Type'] = 'application/json';
      payload = JSON.stringify(opts.body);
    }

    return fetch(path, { method: method, headers: headers, body: payload })
      .then(function (res) {
        return res.text().then(function (text) {
          var json = null;
          try { json = text ? JSON.parse(text) : null; } catch (e) { json = null; }

          if (res.status === 401) {
            clearSession();
            renderAuth();
            throw new ApiError((json && json.message) || '登录已失效,请重新登录', 401);
          }
          if (!json) {
            throw new ApiError('服务器返回异常(HTTP ' + res.status + ')', res.status);
          }
          if (json.code !== 200) {
            throw new ApiError(json.message || '操作失败', json.code);
          }
          return json.data;
        });
      })
      .catch(function (err) {
        if (err instanceof ApiError) throw err;
        throw new ApiError('无法连接服务器,请检查服务是否已启动', 0);
      });
  }

  /* ============================================================
     登录态
     ============================================================ */

  function restoreSession() {
    try {
      state.token = localStorage.getItem(TOKEN_KEY) || null;
      state.user = JSON.parse(localStorage.getItem(USER_KEY) || 'null');
    } catch (e) {
      state.token = null;
      state.user = null;
    }
  }

  function saveSession(token, user) {
    state.token = token;
    state.user = user;
    localStorage.setItem(TOKEN_KEY, token);
    localStorage.setItem(USER_KEY, JSON.stringify(user));
  }

  function clearSession() {
    state.token = null;
    state.user = null;
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(USER_KEY);
  }

  function isAuditor() {
    return !!(state.user && Number(state.user.role) === 1);
  }

  /** 刷新「审核台」入口:只有审核员可见,并显示待审核数量角标 */
  function refreshAuditNav() {
    var btn = document.getElementById('navAudit');
    if (!btn) return;
    if (!isAuditor()) {
      btn.hidden = true;
      btn.textContent = '审核台';
      if (state.view === 'audit') switchView('home');
      return;
    }
    btn.hidden = false;
    api('/admin/stats', { auth: true })
      .then(function (d) {
        var n = Number(d && d.pending) || 0;
        btn.innerHTML = '审核台' + (n > 0 ? '<span class="nav__badge">' + n + '</span>' : '');
      })
      .catch(function () { btn.textContent = '审核台'; });
  }

  function renderAuth() {
    if (state.user) {
      var name = state.user.username || '用户';
      el.authArea.innerHTML =
        '<button class="btn btn--sm" data-act="publish">＋ 发布闲置</button>' +
        '<div class="user-chip">' +
        '<span class="user-chip__avatar">' + esc(name.slice(0, 1).toUpperCase()) + '</span>' +
        '<span>' + esc(name) + '</span>' +
        '</div>' +
        '<button class="btn btn--ghost btn--sm" data-act="logout">退出</button>';
    } else {
      el.authArea.innerHTML =
        '<button class="btn btn--ghost btn--sm" data-act="login">登录</button>' +
        '<button class="btn btn--sm" data-act="register">注册</button>';
    }
    refreshAuditNav();
  }

  function requireLogin() {
    if (state.token) return true;
    toast('请先登录');
    openLogin();
    return false;
  }

  /* ============================================================
     首屏数据
     ============================================================ */

  function loadCategories() {
    return api('/category/list')
      .then(function (list) {
        state.categories = Array.isArray(list) ? list : [];
      })
      .catch(function () {
        state.categories = [];
      });
  }

  function renderCategoryChips() {
    var html = '<button class="chip is-active" data-cid="">全部</button>';
    state.categories.forEach(function (c) {
      html += '<button class="chip" data-cid="' + esc(c.id) + '">' +
        esc(CATEGORY_EMOJI[Number(c.id)] || '') + ' ' + esc(c.name) + '</button>';
    });
    el.categoryChips.innerHTML = html;
  }

  function loadBanners() {
    return api('/banner/list')
      .then(function (list) {
        var items = Array.isArray(list) && list.length
          ? list
          : [
              { title: '让每一件闲置都找到新主人', url: '', sort: 3 },
              { title: '开学季 · 教材书籍专区', url: '/product/list?categoryId=2', sort: 2 },
              { title: '数码好物限时出', url: '/product/list?categoryId=1', sort: 1 }
            ];
        var deco = ['🎓', '📚', '💻'];
        el.banners.innerHTML = items.slice(0, 3).map(function (b, i) {
          var hint = b.url && b.url.indexOf('categoryId') > -1 ? '点击查看该分类' : '校园闲置 · 当面交易';
          return '<div class="banner" data-banner-url="' + esc(b.url || '') + '">' +
            '<span class="banner__deco">' + deco[i % 3] + '</span>' +
            '<div class="banner__title">' + esc(b.title) + '</div>' +
            '<div class="banner__hint">' + hint + '</div>' +
            '</div>';
        }).join('');
      })
      .catch(function () {
        el.banners.innerHTML = '';
      });
  }

  /* ============================================================
     列表渲染
     ============================================================ */

  function renderLoading() {
    var skeletons = '';
    for (var i = 0; i < 8; i++) skeletons += '<div class="skeleton"></div>';
    el.listArea.innerHTML = '<div class="skeleton-grid">' + skeletons + '</div>';
    el.pager.innerHTML = '';
    el.listMeta.textContent = '加载中…';
  }

  function renderError(err) {
    var isAuth = err && err.code === 401;
    var html = '<div class="state">' +
      '<div class="state__icon">' + (isAuth ? '🔐' : '⚠️') + '</div>' +
      '<div class="state__title">' + (isAuth ? '需要登录后查看' : '数据加载失败') + '</div>' +
      '<div class="state__text">' + esc((err && err.message) || '未知错误') + '</div>' +
      '<button class="btn" data-act="' + (isAuth ? 'login' : 'reload') + '">' +
      (isAuth ? '去登录' : '重新加载') + '</button>' +
      '</div>';
    el.listArea.innerHTML = html;
    el.listMeta.textContent = '';
    el.pager.innerHTML = '';
  }

  /** 卡片下方的操作条:我的发布是自家商品管理,审核台是通过/驳回 */
  function cardActions(p, mode) {
    var st = Number(p.status);
    var id = esc(p.id);
    var btns = '';

    if (mode === 'audit') {
      btns = '<button class="btn btn--sm" data-act="approve" data-id="' + id + '">通过</button>' +
        '<button class="btn btn--ghost btn--sm" data-act="reject" data-id="' + id + '">驳回</button>';
    } else {
      if (st === 0 || st === 1) {
        btns += '<button class="btn btn--ghost btn--sm" data-act="offshelf" data-id="' + id + '">下架</button>';
      }
      if (st === 2 || st === 4) {
        btns += '<button class="btn btn--ghost btn--sm" data-act="onshelf" data-id="' + id + '">重新上架</button>';
      }
      if (st === 1) {
        btns += '<button class="btn btn--ghost btn--sm" data-act="sold" data-id="' + id + '">标记已售</button>';
      }
      btns += '<button class="btn btn--danger btn--sm" data-act="del" data-id="' + id + '">删除</button>';
    }
    return '<div class="card-actions">' + btns + '</div>';
  }

  function productCard(p, mode) {
    var cid = p.categoryId != null ? Number(p.categoryId) : null;
    var catName = p.categoryName || categoryNameOf(cid);
    var emoji = categoryEmojiOf(cid, p.categoryName);
    var st = STATUS_TEXT[Number(p.status)] || STATUS_TEXT[1];

    var badge = Number(p.status) !== 1
      ? '<span class="badge-status ' + st.cls + '">' + st.text + '</span>'
      : '';

    var img = p.mainImage
      ? '<img src="' + esc(p.mainImage) + '" alt="" loading="lazy" onerror="this.remove()">'
      : '';

    var oldPrice = p.originalPrice && Number(p.originalPrice) > Number(p.price)
      ? '<span class="price-old">¥' + money(p.originalPrice) + '</span>'
      : '';

    var seller = mode === 'audit' && p.username
      ? '<span>卖家 ' + esc(p.username) + '</span>'
      : '<span>' + (p.viewCount || 0) + ' 浏览</span>';

    var card = '<button class="card-product" data-id="' + esc(p.id) + '">' +
      '<div class="thumb thumb--c' + (cid || 6) + '">' +
      '<span>' + emoji + '</span>' + img + badge +
      '</div>' +
      '<div class="card-product__body">' +
      '<div class="card-product__title">' + esc(p.title) + '</div>' +
      '<div class="price-row"><span class="price">' + money(p.price) + '</span>' + oldPrice + '</div>' +
      '<div class="card-meta"><span>' + esc(catName) + '</span>' + seller + '</div>' +
      '</div>' +
      '</button>';

    if (!mode) return card;
    // 操作按钮不能嵌在 <button> 里(HTML 不允许按钮嵌套),所以外面包一层
    return '<div class="card-wrap">' + card + cardActions(p, mode) + '</div>';
  }

  function renderProducts(records, pageData) {
    var mode = state.view === 'mine' ? 'mine' : (state.view === 'audit' ? 'audit' : '');

    if (!records || !records.length) {
      el.listArea.innerHTML = state.view === 'audit'
        ? '<div class="state">' +
          '<div class="state__icon">✅</div>' +
          '<div class="state__title">没有待审核的商品</div>' +
          '<div class="state__text">所有提交都已处理完毕。</div>' +
          '</div>'
        : '<div class="state">' +
          '<div class="state__icon">🌱</div>' +
          '<div class="state__title">这里还空着</div>' +
          '<div class="state__text">换个关键词或分类试试,也可以自己发布一件闲置。</div>' +
          '<button class="btn" data-act="publish">发布闲置</button>' +
          '</div>';
      el.listMeta.textContent = '共 0 件';
      el.pager.innerHTML = '';
      return;
    }
    el.listArea.innerHTML = '<div class="grid">' + records.map(function (p) {
      return productCard(p, mode);
    }).join('') + '</div>';
    el.listMeta.textContent = '共 ' + pageData.total + ' 件 · 第 ' + pageData.pageNum + '/' + (pageData.pages || 1) + ' 页';
    renderPager(pageData);
  }

  function pageWindow(cur, total) {
    var out = [];
    var push = function (v) { if (out.indexOf(v) === -1) out.push(v); };
    if (total <= 7) {
      for (var i = 1; i <= total; i++) push(i);
      return out;
    }
    push(1);
    if (cur > 4) push('...');
    for (var j = Math.max(2, cur - 1); j <= Math.min(total - 1, cur + 1); j++) push(j);
    if (cur < total - 3) push('...');
    push(total);
    return out;
  }

  function renderPager(pageData) {
    var cur = Number(pageData.pageNum) || 1;
    var total = Number(pageData.pages) || 0;
    if (total <= 1) {
      el.pager.innerHTML = '';
      return;
    }
    var html = '<button data-page="' + (cur - 1) + '"' + (cur <= 1 ? ' disabled' : '') + '>上一页</button>';
    pageWindow(cur, total).forEach(function (p) {
      html += p === '...'
        ? '<button disabled>…</button>'
        : '<button data-page="' + p + '" class="' + (p === cur ? 'is-current' : '') + '">' + p + '</button>';
    });
    html += '<button data-page="' + (cur + 1) + '"' + (cur >= total ? ' disabled' : '') + '>下一页</button>';
    el.pager.innerHTML = html;
  }

  /* ============================================================
     视图切换
     ============================================================ */

  var VIEW_TITLE = {
    home: '最新上架',
    favorites: '我的收藏',
    mine: '我发布的',
    audit: '审核台 · 待审核'
  };

  function switchView(view) {
    if (view !== 'home' && !state.token) {
      toast('请先登录');
      openLogin();
      return;
    }
    if (view === 'audit' && !isAuditor()) {
      toast('只有审核员可以进入审核台');
      return;
    }
    state.view = view;
    state.page = 1;

    Array.prototype.forEach.call(document.querySelectorAll('.nav__item'), function (b) {
      b.classList.toggle('is-active', b.dataset.view === view);
    });

    var isHome = view === 'home';
    el.banners.style.display = isHome ? '' : 'none';
    el.filterBar.style.display = isHome ? '' : 'none';
    el.listTitle.textContent = VIEW_TITLE[view] || '最新上架';

    loadView(1);
  }

  function loadView(page) {
    state.page = page || 1;
    renderLoading();

    var request;
    if (state.view === 'home') {
      var qs = new URLSearchParams();
      qs.set('pageNum', state.page);
      qs.set('pageSize', state.pageSize);
      if (state.keyword) qs.set('keyword', state.keyword);
      if (state.categoryId) qs.set('categoryId', state.categoryId);
      request = api('/product/list?' + qs.toString());
    } else if (state.view === 'audit') {
      // 审核台只拉「待审核」,按提交时间正序,先来先审
      request = api('/admin/products?status=0&pageNum=' + state.page + '&pageSize=' + state.pageSize,
        { auth: true });
    } else {
      var path = state.view === 'favorites' ? '/user/favorites' : '/user/products';
      request = api(path + '?pageNum=' + state.page + '&pageSize=' + state.pageSize, { auth: true });
    }

    request
      .then(function (pageData) { renderProducts(pageData.records, pageData); })
      .catch(renderError);
  }

  function selectCategory(cid) {
    state.categoryId = cid === '' || cid == null ? null : Number(cid);
    Array.prototype.forEach.call(el.categoryChips.querySelectorAll('.chip'), function (c) {
      c.classList.toggle('is-active', String(c.dataset.cid) === (state.categoryId == null ? '' : String(state.categoryId)));
    });
    if (state.view !== 'home') switchView('home');
    else loadView(1);
  }

  /* ============================================================
     弹层
     ============================================================ */

  function openModal(html, wide) {
    el.modalBody.className = 'modal__body' + (wide ? ' modal__body--wide' : '');
    el.modalBody.innerHTML = html;
    el.modal.hidden = false;
    document.body.style.overflow = 'hidden';
  }

  function closeModal() {
    el.modal.hidden = true;
    el.modalBody.innerHTML = '';
    document.body.style.overflow = '';
  }

  function modalShell(title, sub, content) {
    var bodyWrap = content ? '<div class="modal__content">' + content + '</div>' : '';
    return '<div class="modal__head">' +
      '<div><div class="modal__title">' + esc(title) + '</div>' +
      (sub ? '<div class="modal__sub">' + esc(sub) + '</div>' : '') +
      '</div>' +
      '<button class="modal__close" data-close="1" aria-label="关闭">✕</button>' +
      '</div>' + bodyWrap;
  }

  /* ---------------- 商品详情 ---------------- */

  function openDetail(id) {
    openModal('<div class="modal__content"><div class="state">' +
      '<div class="state__icon">⏳</div><div class="state__title">正在加载商品…</div></div></div>', true);

    api('/product/detail/' + id, { optionalAuth: true })
      .then(function (d) { renderDetail(d); })
      .catch(function (err) {
        closeModal();
        toast(err.message);
      });
  }

  function renderDetail(d) {
    var images = Array.isArray(d.images) ? d.images.filter(Boolean) : [];
    var gallery;
    if (images.length) {
      gallery = images.map(function (src) {
        return '<div class="detail-gallery__item">' +
          '<span>' + categoryEmojiOf(null, d.categoryName) + '</span>' +
          '<img src="' + esc(src) + '" alt="" onerror="this.remove()"></div>';
      }).join('');
    } else {
      gallery = '<div class="detail-gallery__item">' +
        '<span>' + categoryEmojiOf(null, d.categoryName) + '</span></div>';
    }

    var st = STATUS_TEXT[Number(d.status)] || STATUS_TEXT[1];
    var seller = d.seller || {};
    var statusNum = Number(d.status);
    var isOwner = !!(state.user && d.userId != null && Number(state.user.id) === Number(d.userId));
    var canManage = isOwner || isAuditor();

    // 卖家/审核员在详情页也能直接操作,不用回到列表
    var manage = '';
    if (canManage) {
      if (statusNum === 0 || statusNum === 1) {
        manage += '<button class="btn btn--ghost" data-act="offshelf" data-id="' + esc(d.id) + '">下架</button>';
      }
      if (statusNum === 2 || statusNum === 4) {
        manage += '<button class="btn" data-act="onshelf" data-id="' + esc(d.id) + '">重新上架</button>';
      }
      if (statusNum === 1 && isOwner) {
        manage += '<button class="btn btn--ghost" data-act="sold" data-id="' + esc(d.id) + '">标记已售</button>';
      }
      manage += '<button class="btn btn--danger" data-act="del" data-id="' + esc(d.id) + '">删除</button>';
    }
    if (isAuditor() && statusNum === 0) {
      manage = '<button class="btn" data-act="approve" data-id="' + esc(d.id) + '">审核通过</button>' +
        '<button class="btn btn--ghost" data-act="reject" data-id="' + esc(d.id) + '">驳回</button>' + manage;
    }

    var html = modalShell(d.title, '商品编号 #' + d.id, '') +
      '<div class="modal__content">' +
      '<div class="detail-gallery">' + gallery + '</div>' +
      '<div class="price-row" style="margin-bottom:16px">' +
      '<span class="price" style="font-size:26px">' + money(d.price) + '</span>' +
      (d.originalPrice ? '<span class="price-old">原价 ¥' + money(d.originalPrice) + '</span>' : '') +
      '</div>' +
      '<dl class="kv">' +
      '<dt>分类</dt><dd>' + esc(d.categoryName || '未分类') + '</dd>' +
      '<dt>状态</dt><dd>' + st.text + '</dd>' +
      (statusNum === 4 && d.auditRemark
        ? '<dt>驳回原因</dt><dd class="kv__danger">' + esc(d.auditRemark) + '</dd>'
        : '') +
      '<dt>浏览</dt><dd>' + (d.viewCount || 0) + ' 次</dd>' +
      '<dt>发布时间</dt><dd>' + fmtTime(d.createTime) + '</dd>' +
      '</dl>' +
      '<div class="detail-desc">' + esc(d.description || '卖家没有填写描述。') + '</div>' +
      (seller.username
        ? '<div class="seller">' +
          '<div class="seller__avatar">' + esc(String(seller.username).slice(0, 1).toUpperCase()) + '</div>' +
          '<div><div class="seller__name">' + esc(seller.username) + '</div>' +
          '<div class="small muted">' + (seller.phone ? '联系电话 ' + esc(seller.phone) : '卖家未留联系方式') + '</div>' +
          '</div></div>'
        : '') +
      '<div class="modal__actions">' +
      '<button class="btn" data-act="favorite" data-pid="' + esc(d.id) + '">☆ 收藏</button>' +
      '<button class="btn btn--ghost" data-act="contact" data-phone="' + esc(seller.phone || '') + '" data-name="' + esc(seller.username || '') + '">联系卖家</button>' +
      '</div>' +
      (manage ? '<div class="modal__actions modal__actions--manage">' + manage + '</div>' : '') +
      '</div>';

    el.modalBody.innerHTML = html;
    markFavoriteState(d.id);
  }

  function markFavoriteState(productId) {
    if (!state.token) return;
    api('/favorites/' + productId, { auth: true })
      .then(function (fav) {
        var btn = el.modalBody.querySelector('[data-act="favorite"]');
        if (!btn) return;
        btn.dataset.fav = fav ? '1' : '0';
        btn.textContent = fav ? '★ 已收藏(点击取消)' : '☆ 收藏';
      })
      .catch(function () { /* 忽略:未登录或接口异常时保持默认文案 */ });
  }

  function toggleFavorite(btn) {
    if (!requireLogin()) return;
    var pid = btn.dataset.pid;
    var isFav = btn.dataset.fav === '1';
    btn.disabled = true;

    api('/favorites/' + pid, { method: isFav ? 'DELETE' : 'POST', auth: true })
      .then(function () {
        btn.dataset.fav = isFav ? '0' : '1';
        btn.textContent = isFav ? '☆ 收藏' : '★ 已收藏(点击取消)';
        toast(isFav ? '已取消收藏' : '已加入收藏');
        if (state.view === 'favorites') loadView(state.page);
      })
      .catch(function (err) { toast(err.message); })
      .then(function () { btn.disabled = false; });
  }

  /* ---------------- 商品管理:下架 / 上架 / 售出 / 删除 ---------------- */

  /** 操作完成后统一收尾:关弹层、刷新当前视图、更新审核台角标 */
  function refreshAfterChange() {
    closeModal();
    if (state.view === 'audit') {
      loadView(1);
    } else {
      loadView(state.page);
    }
    refreshAuditNav();
  }

  function changeStatus(id, status, okMsg) {
    if (!requireLogin()) return;
    api('/product/status/' + id + '?status=' + status, { method: 'PUT', auth: true })
      .then(function () {
        toast(okMsg);
        refreshAfterChange();
      })
      .catch(function (err) { toast(err.message); });
  }

  function removeProduct(id) {
    if (!requireLogin()) return;
    if (!window.confirm('删除后不可恢复,商品图片也会一起删除。确定要删除这件商品吗?')) return;
    api('/product/remove/' + id, { method: 'DELETE', auth: true })
      .then(function () {
        toast('已删除');
        refreshAfterChange();
      })
      .catch(function (err) { toast(err.message); });
  }

  function submitAudit(id, approved, remark) {
    api('/admin/products/' + id + '/audit', {
      method: 'POST',
      auth: true,
      body: { approved: approved, remark: remark || null }
    })
      .then(function () {
        toast(approved ? '已通过,商品已上架' : '已驳回,卖家会看到原因');
        refreshAfterChange();
      })
      .catch(function (err) { toast(err.message); });
  }

  function openReject(id) {
    if (!requireLogin()) return;
    openModal(modalShell('驳回商品', '填写原因,卖家会在「我发布的」里看到', '') +
      '<div class="modal__content">' +
      '<div class="field"><label class="field__label" for="rjReason">驳回原因<span>*</span></label>' +
      '<textarea id="rjReason" maxlength="200" placeholder="例如:图片含水印或联系方式 / 描述与实际不符 / 分类选错了"></textarea></div>' +
      '<div class="modal__actions">' +
      '<button class="btn btn--ghost" data-close="1">取消</button>' +
      '<button class="btn btn--danger" id="rjSubmit">确认驳回</button>' +
      '</div></div>');

    document.getElementById('rjSubmit').addEventListener('click', function () {
      var reason = document.getElementById('rjReason').value.trim();
      if (!reason) {
        toast('请填写驳回原因');
        return;
      }
      submitAudit(id, false, reason);
    });
  }

  /* ---------------- 登录 / 注册 ---------------- */

  function openLogin() {
    openModal(modalShell('登录', '登录后可以收藏商品、发布闲置', '' ) +
      '<div class="modal__content">' +
      '<div class="form-error" id="loginError"></div>' +
      '<form id="loginForm" novalidate>' +
      '<div class="field"><label class="field__label" for="lgUser">用户名<span>*</span></label>' +
      '<input id="lgUser" name="username" autocomplete="username" placeholder="请输入用户名" required></div>' +
      '<div class="field"><label class="field__label" for="lgPwd">密码<span>*</span></label>' +
      '<input id="lgPwd" name="password" type="password" autocomplete="current-password" placeholder="请输入密码" required></div>' +
      '<button class="btn btn--block" type="submit">登录</button>' +
      '</form>' +
      '<p class="form-hint">还没有账号?<a href="#" data-act="register">立即注册</a></p>' +
      '</div>');
    bindLoginForm();
  }

  function bindLoginForm() {
    var form = document.getElementById('loginForm');
    if (!form) return;
    form.addEventListener('submit', function (ev) {
      ev.preventDefault();
      var errBox = document.getElementById('loginError');
      var username = fieldValue(form, 'username').trim();
      var password = fieldValue(form, 'password');

      if (!username || !password) {
        showFormError(errBox, '请输入用户名和密码');
        return;
      }

      var btn = form.querySelector('button[type="submit"]');
      btn.disabled = true;
      btn.textContent = '登录中…';

      api('/auth/login', { method: 'POST', body: { username: username, password: password } })
        .then(function (data) {
          saveSession(data.token, data.user);
          renderAuth();
          closeModal();
          toast('欢迎回来,' + (data.user && data.user.username ? data.user.username : username));
          loadView(1);
        })
        .catch(function (err) {
          showFormError(errBox, err.message);
          btn.disabled = false;
          btn.textContent = '登录';
        });
    });
  }

  function openRegister() {
    openModal(modalShell('注册', '注册成功后会自动登录', '') +
      '<div class="modal__content">' +
      '<div class="form-error" id="regError"></div>' +
      '<form id="regForm" novalidate>' +
      '<div class="field-row">' +
      '<div class="field"><label class="field__label" for="rgUser">用户名<span>*</span></label>' +
      '<input id="rgUser" name="username" autocomplete="username" placeholder="登录账号" required></div>' +
      '<div class="field"><label class="field__label" for="rgPwd">密码<span>*</span></label>' +
      '<input id="rgPwd" name="password" type="password" autocomplete="new-password" placeholder="建议 6 位以上" required></div>' +
      '</div>' +
      '<div class="field-row">' +
      '<div class="field"><label class="field__label" for="rgReal">真实姓名</label>' +
      '<input id="rgReal" name="realName" placeholder="选填"></div>' +
      '<div class="field"><label class="field__label" for="rgSchool">学校</label>' +
      '<input id="rgSchool" name="school" placeholder="选填"></div>' +
      '</div>' +
      '<div class="field-row">' +
      '<div class="field"><label class="field__label" for="rgPhone">手机号</label>' +
      '<input id="rgPhone" name="phone" placeholder="选填,方便买家联系"></div>' +
      '<div class="field"><label class="field__label" for="rgEmail">邮箱</label>' +
      '<input id="rgEmail" name="email" type="email" placeholder="选填"></div>' +
      '</div>' +
      '<button class="btn btn--block" type="submit">注册并登录</button>' +
      '</form>' +
      '<p class="form-hint">已有账号?<a href="#" data-act="login">直接登录</a></p>' +
      '</div>');

    var form = document.getElementById('regForm');
    form.addEventListener('submit', function (ev) {
      ev.preventDefault();
      var errBox = document.getElementById('regError');
      var username = fieldValue(form, 'username').trim();
      var password = fieldValue(form, 'password');

      if (!username || !password) {
        showFormError(errBox, '用户名和密码必填');
        return;
      }
      if (username.length < 3) {
        showFormError(errBox, '用户名至少 3 个字符');
        return;
      }
      if (password.length < 6) {
        showFormError(errBox, '密码至少 6 位');
        return;
      }

      var btn = form.querySelector('button[type="submit"]');
      btn.disabled = true;
      btn.textContent = '提交中…';

      var body = {
        username: username,
        password: password,
        realName: fieldValue(form, 'realName').trim() || null,
        school: fieldValue(form, 'school').trim() || null,
        phone: fieldValue(form, 'phone').trim() || null,
        email: fieldValue(form, 'email').trim() || null
      };

      api('/auth/register', { method: 'POST', body: body })
        .then(function () {
          return api('/auth/login', { method: 'POST', body: { username: username, password: password } });
        })
        .then(function (data) {
          saveSession(data.token, data.user);
          renderAuth();
          closeModal();
          toast('注册成功,已自动登录');
          loadView(1);
        })
        .catch(function (err) {
          showFormError(errBox, err.message);
          btn.disabled = false;
          btn.textContent = '注册并登录';
        });
    });
  }

  function showFormError(box, msg) {
    if (!box) { toast(msg); return; }
    box.textContent = msg;
    box.classList.add('is-show');
  }

  /* ---------------- 发布商品 ---------------- */

  function openPublish() {
    if (!requireLogin()) return;

    var options = state.categories.map(function (c) {
      return '<option value="' + esc(c.id) + '">' + esc(c.name) + '</option>';
    }).join('');

    openModal(modalShell('发布闲置', '填写商品信息,提交后由审核员审核', '') +
      '<div class="modal__content">' +
      '<div class="form-error" id="pubError"></div>' +
      '<form id="pubForm" novalidate>' +
      '<div class="field"><label class="field__label" for="pbTitle">商品标题<span>*</span></label>' +
      '<input id="pbTitle" name="title" maxlength="100" placeholder="例如:九成新 iPad Air 4 64G" required></div>' +
      '<div class="field-row">' +
      '<div class="field"><label class="field__label" for="pbPrice">售价(元)<span>*</span></label>' +
      '<input id="pbPrice" name="price" type="number" min="0" step="0.01" placeholder="0.00" required></div>' +
      '<div class="field"><label class="field__label" for="pbOld">原价(元)</label>' +
      '<input id="pbOld" name="originalPrice" type="number" min="0" step="0.01" placeholder="选填"></div>' +
      '</div>' +
      '<div class="field"><label class="field__label" for="pbCategory">分类</label>' +
      '<select id="pbCategory" name="categoryId">' + options + '</select></div>' +
      '<div class="field"><label class="field__label" for="pbDesc">商品描述</label>' +
      '<textarea id="pbDesc" name="description" maxlength="2000" placeholder="成色、入手时间、是否有配件、交易方式等"></textarea></div>' +
      '<div class="field">' +
      '<label class="field__label">商品图片<span class="field__hint">最多 5 张,第一张作为主图</span></label>' +
      '<div class="uploader">' +
      '<div class="uploader__grid" id="pbPreview"></div>' +
      '<label class="uploader__add" for="pbFiles"><em>＋</em><span>添加图片</span></label>' +
      '</div>' +
      '<input id="pbFiles" type="file" accept="image/png,image/jpeg,image/webp,image/gif" multiple hidden>' +
      '<p class="form-hint">支持 JPG / PNG / WEBP / GIF,单张不超过 5MB</p>' +
      '</div>' +
      '<button class="btn btn--block" type="submit">确认发布</button>' +
      '</form>' +
      '<p class="form-hint">提交后进入审核,审核员通过后才会出现在首页;状态可在「我发布的」里查看。</p>' +
      '</div>');

    var form = document.getElementById('pubForm');
    var fileInput = document.getElementById('pbFiles');
    var preview = document.getElementById('pbPreview');
    var MAX_IMAGES = 5;
    var MAX_BYTES = 5 * 1024 * 1024;
    var picked = [];   // [{ file, url }],url 是本地预览用的 blob 地址

    function renderPicked() {
      preview.innerHTML = picked.map(function (p, i) {
        return '<div class="uploader__item">' +
          '<img src="' + p.url + '" alt="">' +
          (i === 0 ? '<span class="uploader__tag">主图</span>' : '') +
          '<button type="button" class="uploader__del" data-idx="' + i + '" aria-label="移除">✕</button>' +
          '</div>';
      }).join('');
      // 选满后隐藏「添加」入口,避免反复点击却没有反应
      var add = document.querySelector('.uploader__add');
      if (add) add.style.display = picked.length >= MAX_IMAGES ? 'none' : '';
    }

    preview.addEventListener('click', function (ev) {
      var target = ev.target;
      if (!target || typeof target.closest !== 'function') return;
      var del = target.closest('.uploader__del');
      if (!del) return;
      var idx = Number(del.dataset.idx);
      if (!isNaN(idx) && picked[idx]) {
        URL.revokeObjectURL(picked[idx].url);
        picked.splice(idx, 1);
        renderPicked();
      }
    });

    fileInput.addEventListener('change', function () {
      var chosen = Array.prototype.slice.call(fileInput.files || []);
      chosen.forEach(function (f) {
        if (picked.length >= MAX_IMAGES) {
          toast('最多上传 ' + MAX_IMAGES + ' 张图片');
          return;
        }
        if (!/^image\//.test(f.type)) {
          toast('只能选择图片文件');
          return;
        }
        if (f.size > MAX_BYTES) {
          toast(f.name + ' 超过 5MB,已跳过');
          return;
        }
        picked.push({ file: f, url: URL.createObjectURL(f) });
      });
      fileInput.value = '';   // 清空后才能重复选择同一个文件
      renderPicked();
    });

    form.addEventListener('submit', function (ev) {
      ev.preventDefault();
      var errBox = document.getElementById('pubError');
      var title = fieldValue(form, 'title').trim();
      var price = fieldValue(form, 'price');

      if (!title) { showFormError(errBox, '请填写商品标题'); return; }
      if (price === '' || Number(price) < 0) { showFormError(errBox, '请填写正确的售价'); return; }

      var btn = form.querySelector('button[type="submit"]');
      btn.disabled = true;
      btn.textContent = picked.length ? '上传图片中…' : '发布中…';

      var categoryId = fieldValue(form, 'categoryId');
      var originalPrice = fieldValue(form, 'originalPrice');

      // 商品字段与图片放在同一个 FormData,后端一个事务完成建商品 + 存图
      var fd = new FormData();
      fd.append('title', title);
      fd.append('price', price);
      if (categoryId) fd.append('categoryId', categoryId);
      if (originalPrice !== '') fd.append('originalPrice', originalPrice);
      var desc = fieldValue(form, 'description').trim();
      if (desc) fd.append('description', desc);
      picked.forEach(function (p) { fd.append('files', p.file); });

      api('/product/publishWithImages', { method: 'POST', form: fd, auth: true })
        .then(function (id) {
          picked.forEach(function (p) { URL.revokeObjectURL(p.url); });
          closeModal();
          toast('已提交审核,商品编号 #' + id + ',通过后才会出现在首页', 3600);
          switchView('mine');
        })
        .catch(function (err) {
          showFormError(errBox, err.message);
          btn.disabled = false;
          btn.textContent = '确认发布';
        });
    });
  }

  /* ============================================================
     动作分发
     ============================================================ */

  function handleAction(act, node) {
    switch (act) {
      case 'login': openLogin(); break;
      case 'register': openRegister(); break;
      case 'logout':
        clearSession();
        renderAuth();
        toast('已退出登录');
        if (state.view !== 'home') switchView('home');
        else loadView(1);
        break;
      case 'publish': openPublish(); break;
      case 'favorite': toggleFavorite(node); break;
      case 'reload': loadView(state.page); break;
      case 'offshelf':
        changeStatus(node.dataset.id, 2, '已下架,买家将看不到这件商品');
        break;
      case 'onshelf':
        changeStatus(node.dataset.id, 0, '已重新提交审核,等待审核员处理');
        break;
      case 'sold':
        changeStatus(node.dataset.id, 3, '已标记为售出');
        break;
      case 'del': removeProduct(node.dataset.id); break;
      case 'approve': submitAudit(node.dataset.id, true, null); break;
      case 'reject': openReject(node.dataset.id); break;
      case 'contact':
        var phone = node.dataset.phone;
        var name = node.dataset.name || '卖家';
        if (phone) {
          toast(name + ' 的联系电话:' + phone, 4000);
        } else {
          toast(name + ' 没有留下联系方式,可在商品描述里留言或线下约定', 3200);
        }
        break;
      default: break;
    }
  }

  /* ============================================================
     事件绑定
     ============================================================ */

  document.addEventListener('click', function (ev) {
    var target = ev.target;
    // 某些情况下 target 可能是文本节点,统一兜底避免 closest 报错
    if (!target || typeof target.closest !== 'function') return;

    var viewBtn = target.closest('.nav__item[data-view]');
    if (viewBtn) { switchView(viewBtn.dataset.view); return; }

    if (target.closest('[data-close]')) { closeModal(); return; }

    var actBtn = target.closest('[data-act]');
    if (actBtn) { ev.preventDefault(); handleAction(actBtn.dataset.act, actBtn); return; }

    var banner = target.closest('.banner[data-banner-url]');
    if (banner) {
      var url = banner.dataset.bannerUrl || '';
      var m = url.match(/categoryId=(\d+)/);
      if (m) selectCategory(m[1]);
      return;
    }

    var chip = target.closest('.chip[data-cid]');
    if (chip) { selectCategory(chip.dataset.cid); return; }

    var card = target.closest('.card-product');
    if (card && card.dataset.id) { openDetail(card.dataset.id); return; }

    var pageBtn = target.closest('.pager button[data-page]');
    if (pageBtn && !pageBtn.disabled) { loadView(Number(pageBtn.dataset.page)); }
  });

  var searchTimer = null;
  el.searchInput.addEventListener('input', function () {
    clearTimeout(searchTimer);
    var value = el.searchInput.value.trim();
    searchTimer = setTimeout(function () {
      if (value === state.keyword) return;
      state.keyword = value;
      if (state.view !== 'home') switchView('home');
      else loadView(1);
    }, 350);
  });

  document.addEventListener('keydown', function (ev) {
    if (ev.key === 'Escape' && !el.modal.hidden) closeModal();
  });

  /* ============================================================
     启动
     ============================================================ */

  function init() {
    restoreSession();
    renderAuth();
    loadCategories().then(function () {
      renderCategoryChips();
      loadBanners();
      loadView(1);
    });
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();