(() => {
    const input = document.getElementById('avatar');
    const choose = document.getElementById('chooseAvatar');
    const save = document.getElementById('saveAvatar');
    const photo = document.getElementById('profileAvatar');
    const placeholder = document.getElementById('avatarPlaceholder');
    const feedback = document.getElementById('avatarFeedback');
    if (!input || !choose || !save || !photo || !feedback) return;
    const original = photo.getAttribute('src');
    let previewUrl;
    const releasePreview = () => {
        if (previewUrl) URL.revokeObjectURL(previewUrl);
        previewUrl = undefined;
    };
    const restoreOriginal = () => {
        releasePreview();
        if (original) { photo.src = original; photo.classList.remove('hidden'); photo.style.display = ''; }
        else { photo.removeAttribute('src'); photo.classList.add('hidden'); }
        placeholder.style.display = original ? 'none' : 'flex';
    };
    choose.addEventListener('click', () => input.click());
    input.addEventListener('change', () => {
        const file = input.files[0];
        save.disabled = true;
        restoreOriginal();
        feedback.classList.remove('text-red-700');
        if (!file) { feedback.textContent = 'PNG hoặc JPEG, tối đa 2 MB.'; return; }
        if (!['image/png', 'image/jpeg'].includes(file.type) || file.size === 0 || file.size > 2 * 1024 * 1024) {
            feedback.textContent = 'Vui lòng chọn ảnh PNG/JPEG không vượt quá 2 MB.';
            feedback.classList.add('text-red-700'); input.value = ''; return;
        }
        previewUrl = URL.createObjectURL(file);
        photo.src = previewUrl;
        photo.classList.remove('hidden'); photo.style.display = '';
        placeholder.style.display = 'none';
        feedback.textContent = file.name + ' — ảnh chưa được lưu.';
        save.disabled = false;
    });
    window.addEventListener('pagehide', releasePreview);
})();
