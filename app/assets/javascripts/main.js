$.fn.pressKey = function (key, fn) {
    return this.each(function () {
        $(this).keyup(function (e) {
            if(e.keyCode == key) fn(e)
        });
    });
};

function send(key, text) {
    $.ajax("/" + currentTag + "/" + currentLocale + "/" + encodeURIComponent(key), {
        type: 'PUT',
        contentType: 'application/json',
        data: JSON.stringify({
            text: text,
            retranslate: true
        })
    }).done(function (data, status, xhr) {
        window.location.reload();
    });
}

function sendAction(key, action) {
    $.ajax("/" + currentTag + "/" + currentLocale + "/" + encodeURIComponent(key), {
        type: 'POST',
        contentType: 'application/json',
        data: JSON.stringify({
            action: action
        })
    }).done(function (data, status, xhr) {
        window.location.reload();
    });
}

$(function () {
    $("ul.versionTags li.unselected").click(function (event) {
        var dest = event.target.attributes["data-dest"].value;
        window.location.href = "/" + dest + "/" + currentLocale + "/";
    });

    $("ul.locales li.unselected").click(function (event) {
        var dest = event.target.attributes["data-dest"].value;
        window.location.href = "/" + currentTag + "/" + dest + "/";
    });

    var submitNew = function (event) {
        send($("#newKey").val(), $("#newText").val());
    };
    $("#newSubmit").click(submitNew);
    $("#newText").pressKey(13, submitNew);

    $(".record").each(function (idx, elem) {
        var submit = function () {
            send(elem.attributes["data-key"].value, $(".editText", elem).val());
        };
        var action = function (a) { return function () {
            sendAction(elem.attributes["data-key"].value, a);
        }};
        $(".editButton", elem).click(function () {
            $(".nonedit", elem).hide();
            $(".edit", elem).show();
            $("input", elem).focus();
        });
        $(".saveButton", elem).click(submit);
        $(".approveButton", elem).click(action("approve"));
        $(".rejectButton", elem).click(action("reject"));
        $('input', elem).pressKey(13, submit).pressKey(27, function () {
            window.location.reload();
        });
    });

    $("div.edit").hide();
});
