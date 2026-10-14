import 'package:flutter/material.dart';

import '../../theme/app_palette.dart';
import '../../widgets/modal.dart';

/// What the user typed into the edit dialog.
class ItemForm {
  const ItemForm({required this.name, required this.description});

  final String name;
  final String description;
}

/// The dialog for creating or editing a tag or a speaker (`.edit-modal`): a
/// name and a description. Returns null if it was cancelled.
///
/// [validate] returns an error message for a name that cannot be used (e.g. one
/// that already exists); the dialog then stays open and shows it.
Future<ItemForm?> showEditItemDialog(
  BuildContext context, {
  required String title,
  required String nameLabel,
  required String namePlaceholder,
  required String descriptionPlaceholder,
  required String hint,
  String initialName = '',
  String initialDescription = '',
  String? Function(String name)? validate,
}) {
  return showAppModal<ItemForm>(
    context,
    builder: (context) => _EditItemDialog(
      title: title,
      nameLabel: nameLabel,
      namePlaceholder: namePlaceholder,
      descriptionPlaceholder: descriptionPlaceholder,
      hint: hint,
      initialName: initialName,
      initialDescription: initialDescription,
      validate: validate,
    ),
  );
}

class _EditItemDialog extends StatefulWidget {
  const _EditItemDialog({
    required this.title,
    required this.nameLabel,
    required this.namePlaceholder,
    required this.descriptionPlaceholder,
    required this.hint,
    required this.initialName,
    required this.initialDescription,
    required this.validate,
  });

  final String title;
  final String nameLabel;
  final String namePlaceholder;
  final String descriptionPlaceholder;
  final String hint;
  final String initialName;
  final String initialDescription;
  final String? Function(String name)? validate;

  @override
  State<_EditItemDialog> createState() => _EditItemDialogState();
}

class _EditItemDialogState extends State<_EditItemDialog> {
  late final _name = TextEditingController(text: widget.initialName);
  late final _description = TextEditingController(text: widget.initialDescription);
  String? _error;

  @override
  void dispose() {
    _name.dispose();
    _description.dispose();
    super.dispose();
  }

  void _save() {
    final name = _name.text.trim();
    // Like the design: an empty name does nothing.
    if (name.isEmpty) return;
    final error = widget.validate?.call(name);
    if (error != null) {
      setState(() => _error = error);
      return;
    }
    Navigator.of(context).pop(ItemForm(name: name, description: _description.text.trim()));
  }

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    return ModalCard(
      width: 460,
      radius: 18,
      shadow: const BoxShadow(color: Color(0x1F000000), blurRadius: 48, offset: Offset(0, 16)),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          ModalHeader(title: widget.title, onClose: () => Navigator.of(context).pop()),
          _Field(
            label: widget.nameLabel,
            footnote: _error == null
                ? null
                : Text(_error!, style: TextStyle(fontSize: 12, color: p.danger, height: 1.45)),
            child: _Input(
              controller: _name,
              placeholder: widget.namePlaceholder,
              autofocus: true,
              onChanged: (_) {
                if (_error != null) setState(() => _error = null);
              },
              onSubmitted: _save,
            ),
          ),
          _Field(
            label: 'Description',
            textareaGap: true,
            footnote: Text(widget.hint, style: TextStyle(fontSize: 12, color: p.text3, height: 1.45)),
            child: _Input(
              controller: _description,
              placeholder: widget.descriptionPlaceholder,
              multiline: true,
            ),
          ),
          // The field's 14 px bottom margin and the footer's 6 px top margin collapse in CSS.
          ModalFooter(
            buttons: [
              ModalButton(
                label: 'Cancel',
                kind: ModalButtonKind.cancel,
                onPressed: () => Navigator.of(context).pop(),
              ),
              ModalButton(label: 'Save', onPressed: _save),
            ],
          ),
        ],
      ),
    );
  }
}

/// Uppercase caption, the input and a note under it (`.form-field`).
class _Field extends StatelessWidget {
  const _Field({required this.label, required this.child, this.footnote, this.textareaGap = false});

  final String label;
  final Widget child;
  final Widget? footnote;

  /// [child] is a textarea, which needs a larger gap before the note.
  final bool textareaGap;

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    return Padding(
      padding: const EdgeInsets.only(bottom: 14),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text(
            label.toUpperCase(),
            // Chrome rounds this line box up to 14 px.
            style: TextStyle(
              fontSize: 11,
              height: 14 / 11,
              fontWeight: FontWeight.w700,
              letterSpacing: .77,
              color: p.text3,
            ),
          ),
          const SizedBox(height: 6),
          child,
          // A textarea is an inline box in CSS, which leaves ~4 px under it before the note.
          if (footnote != null) ...[SizedBox(height: textareaGap ? 8 : 4), footnote!],
        ],
      ),
    );
  }
}

/// Single-line input or textarea (`.form-input`, `.form-textarea`).
class _Input extends StatelessWidget {
  const _Input({
    required this.controller,
    required this.placeholder,
    this.multiline = false,
    this.autofocus = false,
    this.onChanged,
    this.onSubmitted,
  });

  final TextEditingController controller;
  final String placeholder;
  final bool multiline;
  final bool autofocus;
  final ValueChanged<String>? onChanged;
  final VoidCallback? onSubmitted;

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    OutlineInputBorder border(Color color) => OutlineInputBorder(
      borderRadius: BorderRadius.circular(9),
      borderSide: BorderSide(color: color),
    );
    final field = TextField(
      controller: controller,
      autofocus: autofocus,
      onChanged: onChanged,
      onSubmitted: onSubmitted == null ? null : (_) => onSubmitted!(),
      cursorColor: p.accent,
      minLines: multiline ? 3 : 1,
      maxLines: multiline ? null : 1,
      keyboardType: multiline ? TextInputType.multiline : TextInputType.text,
      textAlignVertical: multiline ? TextAlignVertical.top : TextAlignVertical.center,
      style: multiline
          ? TextStyle(fontSize: 13.5, height: 1.55, color: p.text2)
          : TextStyle(fontSize: 14, color: p.text),
      decoration: InputDecoration(
        isDense: true,
        filled: true,
        fillColor: p.background,
        focusColor: p.surface, // fill while focused
        hintText: placeholder,
        hintStyle: TextStyle(fontSize: multiline ? 13.5 : 14, color: p.text3),
        contentPadding: EdgeInsets.symmetric(horizontal: 12, vertical: multiline ? 9 : 10),
        border: border(p.border),
        enabledBorder: border(p.border),
        focusedBorder: border(p.accent),
      ),
    );
    // `min-height: 84px` for the textarea.
    return multiline ? ConstrainedBox(constraints: const BoxConstraints(minHeight: 84), child: field) : field;
  }
}
