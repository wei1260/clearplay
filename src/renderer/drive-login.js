const $ = id => document.getElementById(id);
window.driveLogin.onState(state => {
  $('name').textContent = `${state.name}官网登录`;
  $('spinner').hidden = state.status !== 'loading';
  $('retry').hidden = state.status !== 'error';
  $('title').textContent = state.status === 'error' ? '登录页暂时没有打开' : '正在加载官网登录页';
  $('status').textContent = state.status === 'ready' ? '官网已加载。登录成功后，返回播放器点击“完成登录”。' : state.status === 'error' ? '页面加载失败，可以刷新后重试。' : '正在连接官网，请稍候…';
  $('detail').textContent = state.detail || '登录密码仅在网盘官网中输入。首次加载可能需要几秒钟。';
});
$('reload').addEventListener('click', () => window.driveLogin.retry());
$('retry').addEventListener('click', () => window.driveLogin.retry());
$('back').addEventListener('click', () => window.driveLogin.close());
