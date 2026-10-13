package com.constantuser.studydiary;

import android.text.Editable;
import android.text.TextWatcher;

/** Пустой адаптер TextWatcher — чтобы не реализовывать все три метода вручную. */
abstract class SimpleTextWatcher implements TextWatcher {
    @Override
    public void beforeTextChanged(CharSequence s, int start, int count, int after) {
    }

    @Override
    public void onTextChanged(CharSequence s, int start, int before, int count) {
    }

    @Override
    public void afterTextChanged(Editable s) {
    }
}
