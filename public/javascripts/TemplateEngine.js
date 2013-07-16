/**
 * Created with IntelliJ IDEA.
 * User: josh
 * Date: 7/8/13
 * Time: 7:09 AM
 * To change this template use File | Settings | File Templates.
 */
var TemplateEngine = (function() {

    var cache = {};

    function load(url, callback) {
        if (cache[url])
            callback($(cache[url]));
        else {
            $.ajax(url, {
                dataType: "html",
                success: function(html) {
                    cache[url] = html;
                    callback($(html));
                }
            });
        }
    }

    function basicMustache(text, data) {
        Object.keys(data).forEach(function (key) {

            // Replace {{key}} if the data is a string or a number
            if (typeof data[key] === "string" || typeof data[key] === "number") {
                var regex = new RegExp("{{" + key + "}}");
                text = text.replace("{{" + key + "}}", data[key])
            }
        });
        return text;
    }

    function replaceText(node, data) {
        var text = node.textContent;
        node.textContent = basicMustache(text, data);
    }

    function replaceAttributes(node, data) {
        // Do text replacement on attributes as well
        for (var i=0; i<node.attributes.length; i++) {
            var attribute = node.attributes[i];
            var text = attribute.value;
            attribute.value = basicMustache(text, data);
        }
    }

    function replaceDataset(node, data) {
        // Do text replacement on attributes as well
        for (var key in node.dataset) {
            var text = node.dataset[key];
            node.dataset[key] = basicMustache(text, data);
        }
    }

    function iterate(node, data, attach) {
        var repeat = node.attributes["tmpl-repeat"];
        var iterator = data[repeat.value];
        var nodes = [node];
        var i, newNode;

        // Remove the attribute
        node.attributes.removeNamedItem("tmpl-repeat");

        if (iterator instanceof Array) {
            // If the array is empty then delete
            if (!iterator.length) {
                node.parentNode.removeChild(node);
            } else {

                // Clone the node
                for (i=1; i<iterator.length; i++) {
                    newNode = node.cloneNode(true);
                    nodes.push(newNode);
                    node.parentNode.appendChild(newNode);
                }

                // Process each of the repeated nodes. An iteration creates a new attach scope
                for (i=0; i<iterator.length; i++) {
                    var newData = $.extend($.extend({}, data), iterator[i]);
                    var scope = {};
                    processNode(nodes[i], newData, attach, scope);

                    // Check to see if there is a closure to do anything with the scope
                    if (node.attributes["tmpl-closure"]) {
                        var closure = data[node.attributes["tmpl-closure"].value];
                        closure(newData, scope);
                    }
                }
            }
        }
    }

    function conditional(node, data, attach, value, scope) {
        var attrName = !!value ? "tmpl-if" : "tmpl-not";
        var condition = data[node.attributes[attrName].value];

        // Remove the attribute
        node.attributes.removeNamedItem(attrName);

        if (!!condition === value) {
            // Allow this node. Keep processing it
            processNode(node, data, attach, scope);
        } else {
            // Not allowed. Remove this node
            node.parentNode.removeChild(node);
        }
    }

    function processNode(node, data, attach, scope) {
        for (var i=0; i<node.childNodes.length; i++) {
            recurseNode(node.childNodes[i], data, attach, scope);
        }

        if (node.attributes["tmpl-attach"]) {
            var attachName = node.attributes["tmpl-attach"].value;
            if (!attach[attachName]) {
                // Nothing is attached. Attach this node under the specified name
                attach[attachName] = node;
            } else {
                // Something is attached. Check if it is either a single node or an array of nodes
                if (attach[attachName] instanceof Array) {
                    // Add this node to the array
                    attach[attachName].push(node);
                } else {
                    // Change the value from a node to an array of nodes.
                    attach[attachName] = [attach[attachName], node];
                }
            }
            node.attributes.removeNamedItem("tmpl-attach");

            // Also attach this node under the specific scope
            scope[attachName] = node;
        }

        replaceAttributes(node, data);
        replaceDataset(node, data);
    }

    function recurseNode(node, data, attach, scope) {
        if (node.nodeType === Node.TEXT_NODE)
            replaceText(node, data);
        else {
            if (node.attributes["tmpl-repeat"])
                iterate(node, data, attach);
            else if (node.attributes["tmpl-if"])
                conditional(node, data, attach, true, scope);
            else if (node.attributes["tmpl-not"])
                conditional(node, data, attach, false, scope);
            else {
                processNode(node, data, attach, scope);
            }
        }
    }

    function render(url, data, callback) {
        load(url, function(nodes) {
            var attach = {};
            for (var i=0; i<nodes.length; i++) {
                recurseNode(nodes[i], data, attach, {});
            }
            callback(nodes, attach);
        });
    }

    return {
        render: render
    };
})();