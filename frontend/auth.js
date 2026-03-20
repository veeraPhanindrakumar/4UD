(function () {
  const AUTH_KEY = 'myproject_auth';

  function readAuth() {
    try {
      return JSON.parse(sessionStorage.getItem(AUTH_KEY) || 'null');
    } catch {
      return null;
    }
  }

  function writeAuth(auth) {
    sessionStorage.setItem(AUTH_KEY, JSON.stringify(auth));
  }

  function clearAuth() {
    sessionStorage.removeItem(AUTH_KEY);
  }

  function requireRole(role) {
    const auth = readAuth();
    if (!auth || auth.role !== role) {
      clearAuth();
      window.location.href = role === 'admin' ? '../login.html' : '../login.html';
    }
    return auth;
  }

  function attachLogout(buttonId, redirectPath) {
    const button = document.getElementById(buttonId);
    if (!button) {
      return;
    }

    button.addEventListener('click', function () {
      clearAuth();
      window.location.href = redirectPath;
    });
  }

  window.MyProjectAuth = {
    readAuth,
    writeAuth,
    clearAuth,
    requireRole,
    attachLogout
  };
})();
